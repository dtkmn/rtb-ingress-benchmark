"""Receiver-owned admission. All mutations run on the process's ASGI event loop.

SDK delivery tasks outlive HTTP waiters. The registry, not a Kafka future, owns
immutable records and their credits until a terminal outcome or actual teardown.
"""
from __future__ import annotations

import asyncio
import json
import logging
import os
from collections import Counter
from dataclasses import dataclass

from .config import Settings
from .kafka import BidPublisher

LOGGER = logging.getLogger(__name__)


class AdmissionRejected(RuntimeError):
    pass


@dataclass(frozen=True)
class OwnedRecord:
    topic: str
    key: bytes
    value: bytes

    @property
    def size(self) -> int:
        return len(self.key) + len(self.value)


@dataclass(frozen=True)
class Candidate:
    attempt: int
    deadline: float


@dataclass(frozen=True)
class Receipt:
    attempt: int
    outcome: asyncio.Future[bool]
    deadline: float


class PreparationLease:
    def __init__(self, pipeline: BoundedPipeline) -> None:
        self.pipeline = pipeline
        self.held = True
        self.handler_owns = True

    def transfer_to_pending_work(self) -> None:
        """The same reservation now belongs to work that outlives its HTTP handler."""
        self.handler_owns = False

    def release_from_handler(self) -> None:
        if self.handler_owns:
            self.release()

    def release(self) -> None:
        if self.held:
            self.held = False
            self.pipeline.preparation_count -= 1
            self.pipeline.changed.set()


class BoundedPipeline:
    def __init__(self, settings: Settings, transport: BidPublisher | None) -> None:
        self.settings = settings
        self.transport = transport
        if settings.workers <= 0:
            raise ValueError("worker count must be positive")
        self.max_records = settings.pipeline_max_records // settings.workers
        self.max_bytes = settings.pipeline_max_bytes // settings.workers
        self.max_preparations = settings.preparation_max_requests // settings.workers
        if min(self.max_records, self.max_bytes, self.max_preparations) < 1:
            raise ValueError("container limits cannot supply a positive share to every worker")
        self.ready = False
        self.closing = False
        self.teardown_confirmed = False
        self.drain_started: float | None = None
        self.drain_result = "not_started"
        self.preparation_count = 0
        self.counts: Counter[str] = Counter({name: 0 for name in [
            "admission_candidates", "admitted", "admission_rejected", "client_succeeded",
            "client_failed", "abandoned_unknown", "preparation_rejected", "deadline_observations",
        ]})
        self.rejections: Counter[str] = Counter()
        self.http_statuses: Counter[str] = Counter()
        self.candidates: dict[int, Candidate] = {}
        self.records: dict[int, tuple[OwnedRecord, asyncio.Future[bool]]] = {}
        self.tasks: dict[int, asyncio.Task[None]] = {}
        self.outstanding_bytes = 0
        self.next_attempt = 0
        self.changed = asyncio.Event()

    def prepare(self) -> PreparationLease | None:
        if self.closing or self.preparation_count >= self.max_preparations:
            self.counts["preparation_rejected"] += 1
            return None
        self.preparation_count += 1
        return PreparationLease(self)

    def candidate(self) -> Candidate:
        self.next_attempt += 1
        candidate = Candidate(self.next_attempt, asyncio.get_running_loop().time()
                              + self.settings.http_outcome_timeout_ms / 1000)
        self.counts["admission_candidates"] += 1
        self.candidates[candidate.attempt] = candidate
        return candidate

    def reject(self, candidate: Candidate, reason: str) -> None:
        if self.candidates.pop(candidate.attempt, None) is not None:
            self.counts["admission_rejected"] += 1
            self.rejections[reason] += 1
            self.changed.set()

    def commit(self, candidate: Candidate, *, topic: str, key: bytes, value: bytes) -> Receipt:
        # Immutable copies are prepared before the atomic (no-await) commit.
        record = OwnedRecord(str(topic), bytes(key), bytes(value))
        reason = None
        if candidate.attempt not in self.candidates:
            reason = "fenced"
        elif self.closing or not self.ready or self.transport is None:
            reason = "unavailable"
        elif asyncio.get_running_loop().time() >= candidate.deadline:
            reason = "deadline"
            self.counts["deadline_observations"] += 1
        elif len(self.records) >= self.max_records:
            reason = "record_capacity"
        elif record.size > self.max_bytes - self.outstanding_bytes:
            reason = "byte_capacity"
        if reason:
            self.reject(candidate, reason)
            raise AdmissionRejected(reason)
        outcome = asyncio.get_running_loop().create_future()
        del self.candidates[candidate.attempt]
        self.records[candidate.attempt] = (record, outcome)
        self.outstanding_bytes += record.size
        self.counts["admitted"] += 1
        self.tasks[candidate.attempt] = asyncio.create_task(self._dispatch(candidate.attempt, record))
        return Receipt(candidate.attempt, outcome, candidate.deadline)

    async def _dispatch(self, attempt: int, record: OwnedRecord) -> None:
        try:
            assert self.transport is not None
            # Always observe the terminal outcome, including enqueue and acks=0.
            await self.transport.publish(topic=record.topic, payload=record.value,
                                         key=record.key, confirm=True)
        except asyncio.CancelledError:
            # Cancellation alone cannot classify unresolved Kafka work.
            raise
        except Exception:
            self.settle(attempt, False)
        else:
            self.settle(attempt, True)
        finally:
            self.tasks.pop(attempt, None)

    def settle(self, attempt: int, success: bool) -> bool:
        entry = self.records.pop(attempt, None)
        if entry is None:
            return False
        record, outcome = entry
        self.outstanding_bytes -= record.size
        self.counts["client_succeeded" if success else "client_failed"] += 1
        if not outcome.done():
            outcome.set_result(success)
        self.changed.set()
        return True

    async def confirm(self, receipt: Receipt) -> bool:
        if asyncio.get_running_loop().time() >= receipt.deadline:
            self.counts["deadline_observations"] += 1
            return False
        try:
            outcome = await asyncio.wait_for(asyncio.shield(receipt.outcome),
                                            max(0, receipt.deadline - asyncio.get_running_loop().time()))
        except TimeoutError:
            self.counts["deadline_observations"] += 1
            return False
        if asyncio.get_running_loop().time() >= receipt.deadline:
            self.counts["deadline_observations"] += 1
            return False
        return outcome

    def close_intake(self) -> None:
        if not self.closing:
            self.closing = True
            self.drain_started = asyncio.get_running_loop().time()
            for candidate in list(self.candidates.values()):
                self.reject(candidate, "draining")
            self.changed.set()

    async def shutdown(self, teardown_timeout: float = 5) -> dict:
        if self.teardown_confirmed:
            return self.snapshot()  # terminal drain is not reclassified by later lifespan cleanup
        self.close_intake()
        LOGGER.info("benchmark_drain_start %s", json.dumps(self.snapshot(), sort_keys=True))
        deadline = self.drain_started + self.settings.drain_timeout_ms / 1000
        while self.records or self.preparation_count or self.candidates:
            self.changed.clear()
            remaining = deadline - asyncio.get_running_loop().time()
            if remaining <= 0:
                break
            try:
                await asyncio.wait_for(self.changed.wait(), remaining)
            except TimeoutError:
                break
        unsettled = bool(self.records or self.preparation_count or self.candidates)
        self.drain_result = "timed_out" if unsettled else "settled"
        if self.transport is not None:
            stop = asyncio.create_task(self.transport.stop())
            try:
                await asyncio.wait_for(asyncio.shield(stop), teardown_timeout)
            except (TimeoutError, Exception):
                # aiokafka.stop() is a flush/close, not an abort API. If it has
                # not completed, retain credits and report the limitation.
                self.drain_result = "teardown_unconfirmed"
                stop.add_done_callback(lambda task: task.exception() if not task.cancelled() else None)
            else:
                self.teardown_confirmed = True
        else:
            self.teardown_confirmed = True
        self.ready = False
        if self.teardown_confirmed:
            tasks = list(self.tasks.values())
            for task in tasks:
                task.cancel()
            await asyncio.gather(*tasks, return_exceptions=True)
            for record, outcome in self.records.values():
                self.counts["abandoned_unknown"] += 1
                if not outcome.done():
                    outcome.set_result(False)
            self.records.clear()
            self.outstanding_bytes = 0
        result = self.snapshot()
        LOGGER.info("benchmark_drain_end %s", json.dumps(result, sort_keys=True))
        return result

    def snapshot(self) -> dict:
        return {**self.counts, "admission_undecided": len(self.candidates),
                "outstanding": len(self.records), "outstanding_bytes": self.outstanding_bytes,
                "preparation_count": self.preparation_count,
                "http_status_counts": dict(self.http_statuses), "admission_rejection_reasons": dict(self.rejections),
                "ready": self.ready, "closing": self.closing, "teardown_confirmed": self.teardown_confirmed,
                "drain_result": self.drain_result, "pid": os.getpid(), "scope": "worker_process",
                "configured_bounds": {"max_records": self.settings.pipeline_max_records,
                                      "max_bytes": self.settings.pipeline_max_bytes,
                                      "max_preparations": self.settings.preparation_max_requests},
                "effective_bounds": {"max_records": self.max_records, "max_bytes": self.max_bytes,
                                     "max_preparations": self.max_preparations, "workers": self.settings.workers,
                                     "http_outcome_timeout_ms": self.settings.http_outcome_timeout_ms,
                                     "drain_timeout_ms": self.settings.drain_timeout_ms}}
