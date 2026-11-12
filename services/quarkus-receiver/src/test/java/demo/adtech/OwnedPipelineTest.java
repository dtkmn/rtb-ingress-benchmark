package demo.adtech;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

class OwnedPipelineTest {
    static final class Client implements OwnedPipeline.Client {
        final BlockingQueue<OwnedPipeline.Record> records = new LinkedBlockingQueue<>();
        final BlockingQueue<Consumer<Throwable>> callbacks = new LinkedBlockingQueue<>();
        volatile boolean throwsOnSend, failedCallbackOnSend, closed;
        @Override public void send(OwnedPipeline.Record record, Consumer<Throwable> callback) {
            records.add(record); callbacks.add(callback);
            if (failedCallbackOnSend) callback.accept(new IllegalStateException("immediate failed SDK callback"));
            if (throwsOnSend) throw new IllegalStateException("immediate SDK failure after receipt");
        }
        @Override public void close() { closed = true; }
        Consumer<Throwable> callback() throws Exception { return Objects.requireNonNull(callbacks.poll(2, TimeUnit.SECONDS)); }
    }
    private OwnedPipeline pipeline(Client client, int count, long bytes, int preparations, long timeout, long drain) {
        return new OwnedPipeline("chosen-topic", client, 1, count, bytes, preparations, Duration.ofMillis(timeout), Duration.ofMillis(drain));
    }
    private static long count(OwnedPipeline pipeline, String key) { return ((Number) pipeline.state().get(key)).longValue(); }
    private static void failure(CompletableFuture<Void> response, int status) throws Exception {
        ExecutionException exception = assertThrows(ExecutionException.class, () -> response.get(2, TimeUnit.SECONDS));
        assertEquals(status, ((OwnedPipeline.Failure) exception.getCause()).status);
    }
    private static void awaitCount(OwnedPipeline pipeline, String key, long expected) {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (count(pipeline, key) != expected && System.nanoTime() < until) java.util.concurrent.locks.LockSupport.parkNanos(100_000);
        assertEquals(expected, count(pipeline, key), key);
    }
    @Test void enqueueReceiptPrecedesOutcomeAndDuplicateCallbacksCannotSettleTwice() throws Exception {
        Client client = new Client();
        try (OwnedPipeline pipeline = pipeline(client, 2, 100, 2, 1000, 20)) {
            pipeline.publish(" id ", () -> new byte[]{7,8}, false, null).get(2, TimeUnit.SECONDS);
            Consumer<Throwable> callback = client.callback();
            assertEquals(1, count(pipeline, "admitted")); assertEquals(1, count(pipeline, "outstanding"));
            callback.accept(null); callback.accept(new Exception("duplicate"));
            assertEquals(1, count(pipeline, "client_succeeded")); assertEquals(0, count(pipeline, "client_failed"));
            assertEquals(0, count(pipeline, "outstanding_bytes"));
        }
    }
    @Test void postCommitImmediateSdkFailureKeepsEnqueue200ButFailsConfirm() throws Exception {
        for (boolean confirm : List.of(false, true)) for (boolean throwsOnSend : List.of(false, true)) {
            Client client = new Client(); client.throwsOnSend = throwsOnSend; client.failedCallbackOnSend = !throwsOnSend;
            try (OwnedPipeline pipeline = pipeline(client, 2, 100, 2, 1000, 20)) {
                CompletableFuture<Void> result = pipeline.publish("k", () -> new byte[]{1}, confirm, null);
                if (confirm) failure(result, 503); else result.get(2, TimeUnit.SECONDS);
                awaitCount(pipeline, "client_failed", 1);
                assertEquals(1, count(pipeline, "admitted")); assertEquals(0, count(pipeline, "admission_rejected"));
            }
        }
    }
    @Test void ownedValueAndUtf8KeySurviveCallerMutation() throws Exception {
        Client client = new Client(); byte[] source = {4,5};
        try (OwnedPipeline pipeline = pipeline(client, 2, 100, 2, 1000, 20)) {
            pipeline.publish(" 💡 ", () -> source, false, null).get(2, TimeUnit.SECONDS);
            OwnedPipeline.Record record = client.records.poll(2, TimeUnit.SECONDS);
            source[0] = 99; byte[] exposed = record.value(); exposed[1] = 99;
            assertArrayEquals(new byte[]{4,5}, record.value());
            assertArrayEquals(" 💡 ".getBytes(java.nio.charset.StandardCharsets.UTF_8), record.keyBytes());
            assertEquals("chosen-topic", record.topic()); client.callback().accept(null);
        }
    }
    @Test void confirmDeadlineRetainsCapacityUntilLateSuccess() throws Exception {
        Client client = new Client();
        try (OwnedPipeline pipeline = pipeline(client, 1, 100, 2, 40, 20)) {
            CompletableFuture<Void> result = pipeline.publish("k", () -> new byte[]{1}, true, null);
            Consumer<Throwable> callback = client.callback();
            failure(result, 503); assertEquals(1, count(pipeline, "outstanding")); assertEquals(2, count(pipeline, "outstanding_bytes"));
            failure(pipeline.publish("j", () -> new byte[]{1}, false, null), 503);
            callback.accept(null);
            assertEquals(1, count(pipeline, "client_succeeded")); assertEquals(0, count(pipeline, "client_failed"));
            assertEquals(1, count(pipeline, "deadline_observations"));
        }
    }
    @Test void separateRecordAndByteCapsRejectWithoutDispatch() throws Exception {
        for (boolean byteCap : List.of(false, true)) {
            Client client = new Client();
            try (OwnedPipeline pipeline = pipeline(client, byteCap ? 10 : 1, byteCap ? 3 : 100, 2, 1000, 20)) {
                pipeline.publish("k", () -> new byte[]{1,2}, false, null).get(2, TimeUnit.SECONDS);
                failure(pipeline.publish("j", () -> new byte[]{3}, false, null), 503);
                assertEquals(1, count(pipeline, "admitted")); assertEquals(1, count(pipeline, "admission_rejected"));
                assertEquals(3, count(pipeline, "outstanding_bytes")); client.callback().accept(null);
                assertEquals(1, client.records.size());
            }
        }
    }
    @Test void finalSlotRaceAdmitsExactlyOneConcurrentCandidate() throws Exception {
        Client client = new Client(); CountDownLatch ready = new CountDownLatch(2), release = new CountDownLatch(1);
        try (OwnedPipeline pipeline = pipeline(client, 1, 100, 2, 1000, 20)) {
            OwnedPipeline.Serializer serializer = () -> { ready.countDown(); assertTrue(release.await(2, TimeUnit.SECONDS)); return new byte[]{1}; };
            CompletableFuture<Void> one = pipeline.publish("same-id", serializer, false, null);
            CompletableFuture<Void> two = pipeline.publish("same-id", serializer, false, null);
            assertTrue(ready.await(2, TimeUnit.SECONDS)); assertEquals(2, count(pipeline, "admission_undecided")); release.countDown();
            CompletableFuture.allOf(one.handle((x,e) -> null), two.handle((x,e) -> null)).get(2, TimeUnit.SECONDS);
            assertNotEquals(one.isCompletedExceptionally(), two.isCompletedExceptionally());
            assertEquals(1, count(pipeline, "admitted")); assertEquals(1, count(pipeline, "admission_rejected"));
            client.callback().accept(null); assertEquals(1, client.records.size());
        }
    }
    @Test void precommitFailureAccountingAndUnavailableProducerNeverDispatch() throws Exception {
        Client client = new Client();
        try (OwnedPipeline pipeline = pipeline(client, 2, 100, 2, 1000, 20)) {
            failure(pipeline.publish("k", () -> { throw new Exception("serialization"); }, false, null), 500);
            assertEquals(1, count(pipeline, "admission_candidates")); assertEquals(1, count(pipeline, "admission_rejected")); assertTrue(client.records.isEmpty());
        }
        AtomicInteger serialization = new AtomicInteger();
        try (OwnedPipeline pipeline = pipeline(null, 2, 100, 2, 1000, 20)) {
            failure(pipeline.publish("k", () -> { serialization.incrementAndGet(); return new byte[]{1}; }, false, null), 503);
            assertEquals(0, serialization.get()); assertEquals(1, count(pipeline, "admission_rejected"));
        }
    }
    @Test void precommitDeadlineFencesLateCommitAndRetainsLivePreparation() throws Exception {
        Client client = new Client(); CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        try (OwnedPipeline pipeline = pipeline(client, 2, 100, 1, 40, 20)) {
            CompletableFuture<Void> result = pipeline.publish("k", () -> { entered.countDown(); release.await(); return new byte[]{1}; }, false, null);
            assertTrue(entered.await(2, TimeUnit.SECONDS)); failure(result, 503);
            assertEquals(1, count(pipeline, "preparation_count")); assertNull(pipeline.prepare());
            release.countDown(); awaitCount(pipeline, "preparation_count", 0);
            assertEquals(0, count(pipeline, "admitted")); assertEquals(1, count(pipeline, "admission_rejected")); assertTrue(client.records.isEmpty());
        } finally { release.countDown(); }
    }
    @Test void preparationIsContainerWideAndCloseIntakeRejectsEveryMode() {
        try (OwnedPipeline pipeline = pipeline(null, 2, 100, 1, 1000, 20)) {
            OwnedPipeline.Preparation lease = pipeline.prepare(); assertNotNull(lease); assertNull(pipeline.prepare());
            lease.close(); lease.close(); assertEquals(0, count(pipeline, "preparation_count"));
            pipeline.closeIntake(); assertNull(pipeline.prepare()); assertEquals(2, count(pipeline, "preparation_rejected"));
        }
    }
    @Test void drainWaitsForClientSettlementAndRecordsCleanFinalState() throws Exception {
        Client client = new Client(); OwnedPipeline pipeline = pipeline(client, 2, 100, 2, 1000, 1000);
        pipeline.publish("k", () -> new byte[]{1}, false, null).get(2, TimeUnit.SECONDS);
        Consumer<Throwable> callback = client.callback();
        CompletableFuture<Void> closing = CompletableFuture.runAsync(pipeline::close);
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (Boolean.TRUE.equals(pipeline.state().get("accepting")) && System.nanoTime() < until) java.util.concurrent.locks.LockSupport.parkNanos(100_000);
        assertEquals(false, pipeline.state().get("accepting")); assertFalse(closing.isDone());
        callback.accept(null); closing.get(2, TimeUnit.SECONDS);
        assertEquals(true, pipeline.state().get("drain_complete")); assertEquals(0, count(pipeline, "abandoned_unknown"));
        assertEquals(1, count(pipeline, "client_succeeded")); assertEquals(0, count(pipeline, "preparation_count"));
    }
    @Test void drainFencesUncancellablePreparationWithoutPrematurelyReleasingItsPermit() throws Exception {
        Client client = new Client(); CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        OwnedPipeline pipeline = pipeline(client, 2, 100, 1, 1000, 1);
        CompletableFuture<Void> result = pipeline.publish("k", () -> {
            entered.countDown(); boolean done = false;
            while (!done) { try { release.await(); done = true; } catch (InterruptedException ignored) { /* deliberately uncancellable fixture */ } }
            return new byte[]{1};
        }, false, null);
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS)); pipeline.close(); failure(result, 503);
            assertEquals(1, count(pipeline, "preparation_count")); assertEquals(false, pipeline.state().get("drain_complete"));
            assertEquals(1, count(pipeline, "admission_rejected")); assertEquals(0, count(pipeline, "admitted"));
            release.countDown(); awaitCount(pipeline, "preparation_count", 0); assertTrue(client.records.isEmpty());
        } finally { release.countDown(); pipeline.close(); }
    }
    @Test void confirmedTeardownWaitsForBlockedDispatchToActuallyEnd() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), clientClosed = new CountDownLatch(1), release = new CountDownLatch(1), exited = new CountDownLatch(1);
        OwnedPipeline.Client client = new OwnedPipeline.Client() {
            @Override public void send(OwnedPipeline.Record record, Consumer<Throwable> completion) {
                entered.countDown(); boolean done = false;
                while (!done) { try { release.await(); done = true; } catch (InterruptedException ignored) {} }
                exited.countDown();
            }
            @Override public void close() { clientClosed.countDown(); }
        };
        OwnedPipeline pipeline = new OwnedPipeline("topic", client,1,2,100,2,Duration.ofSeconds(1),Duration.ofMillis(1));
        try {
            pipeline.publish("k", () -> new byte[]{1},false,null).get(2,TimeUnit.SECONDS); assertTrue(entered.await(2,TimeUnit.SECONDS));
            CompletableFuture<Void> closing=CompletableFuture.runAsync(pipeline::close); assertTrue(clientClosed.await(2,TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> closing.get(100,TimeUnit.MILLISECONDS));
            assertEquals(1,count(pipeline,"outstanding")); assertEquals(false,pipeline.state().get("producer_teardown_confirmed"));
            release.countDown(); closing.get(2,TimeUnit.SECONDS);
            assertEquals(0,exited.getCount()); assertEquals(1,count(pipeline,"abandoned_unknown")); assertEquals(true,pipeline.state().get("producer_teardown_confirmed"));
        } finally { release.countDown(); pipeline.close(); }
    }
    @Test void failedProducerTeardownRetainsUnknownOutstandingCredits() throws Exception {
        BlockingQueue<Consumer<Throwable>> callback=new LinkedBlockingQueue<>();
        OwnedPipeline.Client client=new OwnedPipeline.Client() {
            @Override public void send(OwnedPipeline.Record record,Consumer<Throwable> completion) { callback.add(completion); }
            @Override public void close() { throw new IllegalStateException("teardown unconfirmed"); }
        };
        OwnedPipeline pipeline=new OwnedPipeline("topic",client,1,2,100,2,Duration.ofSeconds(1),Duration.ofMillis(1));
        pipeline.publish("k",()->new byte[]{1},false,null).get(2,TimeUnit.SECONDS);
        Consumer<Throwable> outcome=callback.poll(2,TimeUnit.SECONDS); assertNotNull(outcome);
        assertThrows(IllegalStateException.class,pipeline::close);
        assertEquals(false,pipeline.state().get("producer_teardown_confirmed")); assertEquals(1,count(pipeline,"outstanding"));
        assertEquals(2,count(pipeline,"outstanding_bytes")); assertEquals(0,count(pipeline,"abandoned_unknown"));
        outcome.accept(new Exception("eventually settled")); assertEquals(1,count(pipeline,"client_failed")); assertEquals(0,count(pipeline,"outstanding_bytes"));
    }
    @Test void blockedClientCloseHasBoundedWaitAndLateFinishCannotAbandonOwnedWork() throws Exception {
        CountDownLatch closeEntered = new CountDownLatch(1), releaseClose = new CountDownLatch(1), closeExited = new CountDownLatch(1);
        BlockingQueue<Consumer<Throwable>> callbacks = new LinkedBlockingQueue<>();
        OwnedPipeline.Client client = new OwnedPipeline.Client() {
            @Override public void send(OwnedPipeline.Record record, Consumer<Throwable> completion) { callbacks.add(completion); }
            @Override public void close() {
                closeEntered.countDown();
                boolean done = false;
                while (!done) { try { releaseClose.await(); done = true; } catch (InterruptedException ignored) {} }
                closeExited.countDown();
            }
        };
        OwnedPipeline pipeline = new OwnedPipeline("topic", client, 1, 2, 100, 2,
                Duration.ofSeconds(1), Duration.ofMillis(1), Duration.ofMillis(40));
        try {
            pipeline.publish("k", () -> new byte[]{1}, false, null).get(2, TimeUnit.SECONDS);
            Consumer<Throwable> outcome = callbacks.poll(2, TimeUnit.SECONDS); assertNotNull(outcome);
            CompletableFuture<Void> closing = CompletableFuture.runAsync(pipeline::close);
            assertTrue(closeEntered.await(2, TimeUnit.SECONDS)); closing.get(500, TimeUnit.MILLISECONDS);
            assertEquals(1, closeExited.getCount()); assertEquals(false, pipeline.state().get("producer_teardown_confirmed"));
            assertEquals(false, pipeline.state().get("drain_complete")); assertEquals(1, count(pipeline, "outstanding"));
            assertEquals(2, count(pipeline, "outstanding_bytes")); assertEquals(0, count(pipeline, "abandoned_unknown"));
            releaseClose.countDown(); assertTrue(closeExited.await(2, TimeUnit.SECONDS));
            assertEquals(false, pipeline.state().get("producer_teardown_confirmed"));
            assertEquals(1, count(pipeline, "outstanding")); assertEquals(0, count(pipeline, "abandoned_unknown"));
            outcome.accept(new Exception("late actual outcome"));
            assertEquals(1, count(pipeline, "client_failed")); assertEquals(0, count(pipeline, "outstanding_bytes"));
        } finally { releaseClose.countDown(); pipeline.close(); }
    }
    @Test void drainOnlyAbandonsAfterConfirmedTeardownAndFencesLateCallback() throws Exception {
        Client client = new Client(); OwnedPipeline pipeline = pipeline(client, 2, 100, 2, 1000, 1);
        pipeline.publish("k", () -> new byte[]{1}, false, null).get(2, TimeUnit.SECONDS); Consumer<Throwable> callback = client.callback();
        pipeline.close(); assertTrue(client.closed); assertEquals(true, pipeline.state().get("drain_timed_out"));
        assertEquals(1, count(pipeline, "abandoned_unknown")); assertEquals(0, count(pipeline, "outstanding_bytes"));
        callback.accept(null); assertEquals(0, count(pipeline, "client_succeeded")); assertEquals(1, count(pipeline, "abandoned_unknown"));
        assertEquals(true, pipeline.state().get("producer_teardown_confirmed"));
    }
}
