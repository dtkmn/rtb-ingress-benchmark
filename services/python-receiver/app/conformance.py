"""Opt-in bounded schedules around the real handler and owned pipeline.

This controlled transport proves pipeline behavior, never Kafka delivery.
Disabled applications construct no controller, futures, event ledger or captures.
"""
from __future__ import annotations

import asyncio
import base64
import hmac
import os

from .pipeline import AdmissionRejected

FAULTS = frozenset({
    "serialization_failure", "producer_unavailable", "admission_throw", "admission_failed_future",
    "held_admission_then_success", "held_delivery_then_success", "delivery_failure_after_receipt",
    "response_deadline_then_late_success", "backlog_record_limit", "backlog_byte_limit",
    "preparation_limit", "final_slot_race", "drain_deadline_unsettled", "confirm_ack_zero",
    "held_admission_then_rejection", "admission_deadline_then_late_receipt",
    "duplicate_completion_callback", "source_mutation_after_receipt",
})
ADMISSION_HOLDS = frozenset({"held_admission_then_success", "held_admission_then_rejection",
                            "admission_deadline_then_late_receipt", "final_slot_race"})
DELIVERY_HOLDS = frozenset({"held_admission_then_success", "held_delivery_then_success",
    "delivery_failure_after_receipt", "response_deadline_then_late_success", "final_slot_race",
    "drain_deadline_unsettled", "duplicate_completion_callback", "source_mutation_after_receipt"})


def record_view(topic, key, value):
    return {"topic": topic, "key_base64": base64.b64encode(key).decode("ascii"),
            "value_base64": base64.b64encode(value).decode("ascii")}


class ConformanceController:
    @classmethod
    def from_env(cls, settings, pipeline):
        if os.getenv("BENCHMARK_CONFORMANCE") != "1":
            return None
        token = os.getenv("BENCHMARK_CONFORMANCE_TOKEN", "")
        if not token or len(token) > 256:
            raise ValueError("conformance requires a nonempty token of at most 256 characters")
        if settings.workers != 1:
            raise ValueError("conformance schedules require exactly one serving worker")
        return cls(settings, pipeline, token)

    def __init__(self, settings, pipeline, token):
        self.settings, self.pipeline, self.token = settings, pipeline, token
        self.case_id = self.fault = None
        self.events, self.records, self.receipts, self.sources = [], [], {}, {}
        self.held_admission, self.held_delivery, self.late = {}, {}, {}
        self.setup_attempts, self.leases = set(), []
        self.setup = {"records": 0, "bytes": 0, "preparations": 0}
        self.baseline = None
        self.mutation = self.drain = None
        self.admission_release = self.delivery_release = self.setup_release = None
        self.closed = False
        self.deadline_handles = []
        self.deadlines = {}

    def authorized(self, token):
        return isinstance(token, str) and hmac.compare_digest(token.encode(), self.token.encode())

    def event(self, name, **details):
        if len(self.events) >= 256:
            raise RuntimeError("conformance event bound exceeded")
        self.events.append({"sequence": len(self.events) + 1, "name": name, **details})

    async def start(self):
        pass

    async def stop(self):
        # No callback can be released after this transport teardown boundary.
        self.closed = True
        if self.late:
            await self.release_admission(False, enforce_race=False)
        self.event("teardown_confirmed", state=self.pipeline.snapshot())

    def serialization_boundary(self):
        if self.fault == "serialization_failure":
            self.event("serialization_failure")
            raise ValueError("injected serialization failure")

    async def admit(self, candidate, *, preparation_lease, topic, key, value):
        if len(self.receipts) + len(self.held_admission) >= 2:
            self.pipeline.reject(candidate, "fixture_bound")
            raise AdmissionRejected("fixture accepts at most two contenders")
        self.deadlines[candidate.attempt] = candidate.deadline
        source = {"topic": topic, "key": key, "value": value}
        if self.fault == "source_mutation_after_receipt":
            # These are the caller buffers actually supplied to commit, which copies them.
            source = {"topic": topic, "key": bytearray(key), "value": bytearray(value)}
            self.sources[candidate.attempt] = source
        if self.fault in ("admission_throw", "admission_failed_future"):
            self.pipeline.reject(candidate, self.fault)
            self.event("admission_rejected", key=key.decode(), attempt=candidate.attempt)
            error = AdmissionRejected("injected pre-receipt admission rejection")
            if self.fault == "admission_failed_future":
                failed = asyncio.get_running_loop().create_future()
                failed.set_exception(error)
                await failed
            raise error
        if self.fault in ADMISSION_HOLDS:
            self.held_admission[candidate.attempt] = candidate
            self.event("admission_held", key=key.decode(), attempt=candidate.attempt)
            try:
                accepted = await asyncio.wait_for(asyncio.shield(self.admission_release),
                    max(0, candidate.deadline - asyncio.get_running_loop().time()))
            except asyncio.CancelledError:
                self.pipeline.reject(candidate, "admission_cancelled")
                self.event("admission_rejected", key=key.decode(), attempt=candidate.attempt, reason="HTTP task cancelled before receipt")
                raise
            except TimeoutError:
                # Execute the real expired commit check, retaining the same attempt for a late fence.
                try:
                    self.pipeline.commit(candidate, **source)
                except AdmissionRejected:
                    self.event("deadline_observed", key=key.decode(), attempt=candidate.attempt)
                else:
                    raise RuntimeError("expired candidate unexpectedly committed")
                preparation_lease.transfer_to_pending_work()
                self.late[candidate.attempt] = (candidate, source, preparation_lease)
                raise AdmissionRejected("admission deadline")
            finally:
                self.held_admission.pop(candidate.attempt, None)
            if not accepted:
                self.pipeline.reject(candidate, "deferred_rejection")
                self.event("admission_rejected", key=key.decode(), attempt=candidate.attempt)
                raise AdmissionRejected("deferred rejection")
        try:
            receipt = self.pipeline.commit(candidate, **source)
        except AdmissionRejected as error:
            self.event("admission_rejected", key=key.decode(), attempt=candidate.attempt, reason=str(error))
            raise
        self.receipts[receipt.attempt] = key.decode()
        self.event("receipt_committed", key=key.decode(), attempt=receipt.attempt)
        if self.fault == "response_deadline_then_late_success":
            def observe_deadline():
                if receipt.attempt in self.pipeline.records:
                    self.event("deadline_observed", key=key.decode(), attempt=receipt.attempt,
                               evidence="actual receipt monotonic deadline elapsed with ownership retained")
            self.deadline_handles.append(asyncio.get_running_loop().call_at(receipt.deadline, observe_deadline))
        return receipt

    async def publish(self, *, topic, payload, key, confirm):
        attempt = next((attempt for attempt, (record, _outcome) in self.pipeline.records.items()
                        if record.key is key and record.value is payload), None)
        if attempt in self.setup_attempts:
            await asyncio.shield(self.setup_release)
            return
        if self.fault in DELIVERY_HOLDS:
            self.held_delivery[attempt] = key.decode()
            self.event("delivery_held", key=key.decode(), attempt=attempt)
        if self.fault == "source_mutation_after_receipt":
            # Capture the actual owned transport arguments only after caller storage reuse.
            success = await asyncio.shield(self.delivery_release)
            if self.closed:
                raise asyncio.CancelledError()
            self.records.append(record_view(topic, key, payload))
        else:
            self.records.append(record_view(topic, key, payload))
            success = await asyncio.shield(self.delivery_release) if self.fault in DELIVERY_HOLDS else True
        self.held_delivery.pop(attempt, None)
        if self.closed:
            raise asyncio.CancelledError()
        self.event("client_succeeded" if success else "client_failed", key=key.decode(), attempt=attempt)
        if not success:
            raise RuntimeError("injected post-receipt client failure")

    async def configure(self, case_id, fault):
        if fault not in FAULTS or not isinstance(case_id, str) or not 0 < len(case_id) <= 128:
            raise ValueError("unknown fault or invalid case ID")
        state = self.pipeline.snapshot()
        if self.closed or state["closing"] or any(state[name] for name in ("outstanding", "admission_undecided", "preparation_count")):
            raise ValueError("previous live work must settle through cleanup before configure")
        if (self.pipeline.max_records, self.pipeline.max_bytes, self.pipeline.max_preparations) != (1024, 16777216, 128):
            raise ValueError("reference fault capacities must remain 1024 records/16 MiB/128 preparations")
        self.case_id, self.fault = case_id, fault
        self.events, self.records, self.receipts, self.sources = [], [], {}, {}
        self.held_admission, self.held_delivery, self.late = {}, {}, {}
        self.deadlines = {}
        self.setup_attempts, self.leases = set(), []
        self.setup = {"records": 0, "bytes": 0, "preparations": 0}
        self.mutation = self.drain = None
        loop = asyncio.get_running_loop()
        self.admission_release, self.delivery_release, self.setup_release = (loop.create_future() for _ in range(3))
        if fault == "preparation_limit":
            self.leases = [self.pipeline.prepare() for _ in range(128)]
            if any(lease is None for lease in self.leases):
                raise RuntimeError("reference preparation setup failed")
            self.setup["preparations"] = len(self.leases)
        elif self.settings.uses_kafka():
            self.pipeline.ready = fault != "producer_unavailable"
            count = 1024 if fault == "backlog_record_limit" else 1023 if fault == "final_slot_race" else 0
            byte_goal = self.pipeline.max_bytes - 1 if fault == "backlog_byte_limit" else 0
            remaining = byte_goal
            index = 0
            while index < count or remaining:
                key = f"__setup__{index}".encode()
                size = min(65536, remaining) if remaining else len(key) + 1
                if size <= len(key):
                    raise RuntimeError("setup key exceeds remaining byte chunk")
                value = b"x" * (size - len(key))
                receipt = self.pipeline.commit(self.pipeline.candidate(), topic=self.settings.kafka_topic, key=key, value=value)
                self.setup_attempts.add(receipt.attempt)
                self.setup["records"] += 1
                self.setup["bytes"] += size
                remaining -= size if remaining else 0
                index += 1
        self.baseline = self.pipeline.snapshot()

    async def cleanup(self):
        if self.closed or self.pipeline.closing:
            return
        for handle in self.deadline_handles:
            handle.cancel()
        self.deadline_handles.clear()
        await self.release_admission(True, enforce_race=False)
        self.release_delivery(True)
        if not self.setup_release.done():
            self.setup_release.set_result(True)
        for lease in self.leases:
            lease.release()
        self.leases.clear()
        async def settled():
            while self.pipeline.records or self.pipeline.candidates or self.pipeline.preparation_count:
                self.pipeline.changed.clear()
                await self.pipeline.changed.wait()
        await asyncio.wait_for(settled(), 5)
        self.pipeline.ready = self.settings.uses_kafka()

    async def release_admission(self, accepted, enforce_race=True):
        if self.fault == "final_slot_race" and enforce_race and len(self.held_admission) != 2:
            raise ValueError("race requires two observed real candidates at the common barrier")
        if self.admission_release is not None and not self.admission_release.done():
            self.admission_release.set_result(accepted)
            self.event("admission_released" if accepted else "admission_rejected")
        for attempt, (candidate, source, preparation_lease) in list(self.late.items()):
            try:
                try:
                    self.pipeline.commit(candidate, **source)
                except AdmissionRejected as error:
                    self.event("late_receipt_fenced", attempt=attempt, key=bytes(source["key"]).decode(), reason=str(error))
                else:
                    raise RuntimeError("late candidate escaped its actual commit fence")
            finally:
                del self.late[attempt]
                preparation_lease.release()

    def release_delivery(self, succeeded):
        if self.closed:
            raise ValueError("transport already torn down")
        if self.delivery_release is not None and not self.delivery_release.done():
            self.delivery_release.set_result(succeeded)
            self.event("delivery_released", succeeded=succeeded)

    def mutate_source(self):
        if not self.sources:
            raise ValueError("no original caller buffers retained")
        mutations = []
        for source in self.sources.values():
            before = record_view(**source)
            source["topic"] = "mutated-caller-topic"
            source["key"][:] = b"mutated-caller-key"
            source["value"][:] = b"mutated-caller-value"
            mutations.append({"before": before, "after": record_view(**source)})
        self.mutation = {"performed": True,
            "evidence": "actual mutable caller key/value bytearrays copied by pipeline.commit into immutable owned bytes before receipt; caller storage then mutated before dispatch",
            "before_value_base64": mutations[0]["before"]["value_base64"],
            "after_value_base64": mutations[0]["after"]["value_base64"], "sources": mutations}
        self.event("source_mutated")

    def duplicate_completion(self):
        if not self.receipts:
            raise ValueError("no fixture receipt")
        for attempt, key in self.receipts.items():
            if attempt in self.pipeline.records:
                raise ValueError("first real client outcome must settle before duplicate")
            before = self.pipeline.snapshot()
            applied = self.pipeline.settle(attempt, True)
            self.event("duplicate_completion", key=key, attempt=attempt, applied=applied,
                       before=before, after=self.pipeline.snapshot())

    async def command(self, command):
        action = command.get("action")
        if self.settings.is_http_only() and action in {"release_admission", "reject_admission", "release_delivery",
                "fail_delivery", "mutate_source", "duplicate_completion", "drain"}:
            return self.snapshot()  # no producer/record fault is invoked in this mode
        if action == "configure":
            await self.configure(command.get("case_id"), command.get("fault"))
        elif action == "release_admission":
            await self.release_admission(True)
        elif action == "reject_admission":
            await self.release_admission(False)
        elif action == "release_delivery":
            self.release_delivery(True)
        elif action == "fail_delivery":
            self.release_delivery(False)
        elif action == "mutate_source":
            self.mutate_source()
        elif action == "duplicate_completion":
            self.duplicate_completion()
        elif action == "cleanup":
            await self.cleanup()
        elif action == "drain":
            before = self.pipeline.snapshot()
            self.pipeline.close_intake()
            self.event("drain_started", state=self.pipeline.snapshot())
            after = await self.pipeline.shutdown()
            self.drain = {"before": before, "after": after}
        else:
            raise ValueError("unknown command")
        return self.snapshot()

    def snapshot(self):
        return {"enabled": True, "case_id": self.case_id, "fault": self.fault, "events": list(self.events),
                "state": self.pipeline.snapshot(), "baseline": self.baseline,
                "held_admission": len(self.held_admission), "held_delivery": len(self.held_delivery),
                "deadline_elapsed": bool(self.deadlines) and all(asyncio.get_running_loop().time() >= deadline for deadline in self.deadlines.values()),
                "records": list(self.records), "setup": self.setup, "mutation": self.mutation, "drain": self.drain}
