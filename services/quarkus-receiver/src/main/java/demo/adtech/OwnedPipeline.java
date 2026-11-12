package demo.adtech;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Container-wide ownership boundary; SDK buffers are not admission receipts. */
public final class OwnedPipeline implements AutoCloseable {
    @FunctionalInterface public interface Serializer { byte[] serialize() throws Exception; }
    public interface Client {
        void send(Record record, java.util.function.Consumer<Throwable> completion);
        void close();
    }
    public static final class Failure extends RuntimeException {
        public final int status;
        Failure(int status, String reason, Throwable cause) { super(reason, cause); this.status = status; }
    }
    public static final class Record {
        private final String topic, key;
        private final byte[] keyBytes, value;
        Record(String topic, String key, byte[] value) {
            this.topic = Objects.requireNonNull(topic); this.key = Objects.requireNonNull(key);
            this.keyBytes = key.getBytes(StandardCharsets.UTF_8); this.value = value.clone();
        }
        public String topic() { return topic; }
        public String key() { return key; }
        public byte[] keyBytes() { return keyBytes.clone(); }
        public byte[] value() { return value.clone(); }
        long bytes() { return (long) keyBytes.length + value.length; }
    }
    public final class Preparation implements AutoCloseable {
        private int references = 1;
        private final AtomicBoolean requestReleased = new AtomicBoolean();
        public AutoCloseable retain() {
            synchronized (lock) {
                if (references == 0) throw new Failure(503, "preparation ended", null);
                references++;
            }
            AtomicBoolean released = new AtomicBoolean();
            return () -> { if (released.compareAndSet(false, true)) release(); };
        }
        private void release() {
            synchronized (lock) { if (--references == 0) { preparations--; lock.notifyAll(); } }
        }
        @Override public void close() { if (requestReleased.compareAndSet(false, true)) release(); }
    }
    private static final class Attempt {
        final long id, deadline;
        final boolean confirm;
        final String key;
        final CompletableFuture<Void> response = new CompletableFuture<>();
        Record record;
        int phase; // 0 undecided, 1 admitted, 2 rejected, 3 terminal
        ScheduledFuture<?> timer;
        Attempt(long id, long deadline, boolean confirm, String key) { this.id = id; this.deadline = deadline; this.confirm = confirm; this.key=key; }
    }
    private volatile ConformanceAdapter conformance;
    void installConformance(ConformanceAdapter adapter) { conformance=adapter; }
    private final Object lock = new Object();
    private final String topic;
    private final Client client;
    private final int maxRecords, maxPreparations;
    private final long maxBytes, timeoutNanos, drainNanos, teardownNanos;
    private final ExecutorService preparationExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final ThreadPoolExecutor dispatch;
    private final ScheduledThreadPoolExecutor timer;
    private final Map<Long, Attempt> attempts = new HashMap<>();
    private final Map<String, Long> rejectionReasons = new TreeMap<>();
    private final Map<String, Long> httpStatuses = new TreeMap<>();
    private long sequence, candidates, admitted, rejected, succeeded, failed, abandoned, outstandingBytes, deadlines, preparationRejected;
    private int preparations;
    private boolean accepting = true, closing, teardown, closed, drainComplete, drainTimedOut;
    private Map<String, Object> drainBefore = Map.of(), drainAfter = Map.of();

    public OwnedPipeline(String topic, Client client, int workers) {
        this(topic, client, workers, envInt("BENCHMARK_PIPELINE_MAX_RECORDS", 1024),
                envLong("BENCHMARK_PIPELINE_MAX_BYTES", 16777216), envInt("BENCHMARK_PREPARATION_MAX_REQUESTS", 128),
                Duration.ofMillis(envLong("BENCHMARK_HTTP_OUTCOME_TIMEOUT_MS", 30000)),
                Duration.ofMillis(envLong("BENCHMARK_DRAIN_TIMEOUT_MS", 30000)));
    }
    OwnedPipeline(String topic, Client client, int workers, int records, long bytes, int preparationLimit, Duration outcome, Duration drain) {
        this(topic, client, workers, records, bytes, preparationLimit, outcome, drain, Duration.ofSeconds(5));
    }
    OwnedPipeline(String topic, Client client, int workers, int records, long bytes, int preparationLimit, Duration outcome, Duration drain, Duration teardownBudget) {
        if (records < 1 || bytes < 1 || preparationLimit < 1 || workers < 1 || outcome.isNegative() || outcome.isZero() || drain.isNegative() || teardownBudget.isNegative() || teardownBudget.isZero()) throw new IllegalArgumentException("positive pipeline bounds required");
        this.topic = topic; this.client = client; maxRecords = records; maxBytes = bytes; maxPreparations = preparationLimit;
        timeoutNanos = outcome.toNanos(); drainNanos = drain.toNanos(); teardownNanos = teardownBudget.toNanos();
        ThreadFactory threads = Thread.ofPlatform().daemon().name("owned-pipeline-", 0).factory();
        dispatch = new ThreadPoolExecutor(workers, workers, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(records), threads);
        timer = new ScheduledThreadPoolExecutor(1, Thread.ofPlatform().daemon().name("pipeline-deadline-", 0).factory());
        timer.setRemoveOnCancelPolicy(true);
    }
    public Preparation prepare() {
        synchronized (lock) {
            if (!accepting || preparations >= maxPreparations) { preparationRejected++; return null; }
            preparations++; return new Preparation();
        }
    }
    public CompletableFuture<Void> publish(String key, Serializer serializer, boolean confirm, Preparation preparation) {
        Preparation lease = preparation;
        boolean ownLease = lease == null;
        if (ownLease) lease = prepare();
        if (lease == null) return CompletableFuture.failedFuture(new Failure(503, "preparation unavailable", null));
        AutoCloseable work;
        try { work = lease.retain(); } catch (RuntimeException failure) { return CompletableFuture.failedFuture(failure); }
        if (ownLease) lease.close();
        Attempt attempt;
        synchronized (lock) {
            attempt = new Attempt(++sequence, System.nanoTime() + timeoutNanos, confirm, key);
            candidates++; attempts.put(attempt.id, attempt);
            if (conformance!=null) conformance.candidate(key,attempt.deadline);
            if (!accepting || client == null) reject(attempt, "producer unavailable", null, 503);
            else attempt.timer = timer.schedule(() -> expire(attempt), timeoutNanos, TimeUnit.NANOSECONDS);
        }
        if (attempt.phase != 0) { closeWork(work); return attempt.response; }
        try {
            preparationExecutor.execute(() -> {
                try {
                    if (conformance!=null) conformance.beforeSerialization(key);
                    byte[] source=serializer.serialize();
                    if (conformance!=null) conformance.source(key,source);
                    Record record = new Record(topic, key, source);
                    if (conformance!=null) conformance.admission(record).toCompletableFuture().join();
                    synchronized (lock) {
                        if (attempt.phase != 0) { if (conformance!=null) conformance.lateReceipt(key);return; }
                        if (!accepting || System.nanoTime() >= attempt.deadline) { reject(attempt, "admission closed/deadline", null, 503); return; }
                        long count = admitted - succeeded - failed - abandoned;
                        if (count >= maxRecords || record.bytes() > maxBytes - outstandingBytes) { reject(attempt, "capacity", null, 503); return; }
                        attempt.record = record; attempt.phase = 1; admitted++; outstandingBytes += record.bytes();
                        if (conformance!=null) conformance.committed(record);
                        // Submission is protected by the intake lock. A post-commit dispatch failure settles owned work.
                        try { dispatch.execute(() -> dispatch(attempt)); }
                        catch (RuntimeException failure) { settle(attempt, failure); }
                        if (!confirm) { cancelTimer(attempt); attempt.response.complete(null); }
                    }
                } catch (Throwable failure) {
                    Throwable actual=failure instanceof CompletionException && failure.getCause()!=null?failure.getCause():failure;
                    synchronized (lock) { if (attempt.phase == 0) reject(attempt, conformance!=null && actual instanceof Failure?"conformance admission":"serialization", actual, conformance!=null && actual instanceof Failure injected?injected.status:500); }
                } finally { closeWork(work); }
            });
        } catch (RuntimeException failure) {
            synchronized (lock) { reject(attempt, "preparation closed", failure, 503); }
            closeWork(work);
        }
        return attempt.response;
    }
    private void dispatch(Attempt attempt) {
        synchronized (lock) { if (teardown || attempt.phase != 1) return; }
        try { client.send(attempt.record, failure -> { synchronized (lock) { settle(attempt, failure); } }); }
        catch (Throwable failure) { synchronized (lock) { settle(attempt, failure); } }
    }
    private void reject(Attempt attempt, String reason, Throwable cause, int status) {
        if (attempt.phase != 0) return;
        if (conformance!=null) conformance.rejected(attempt.key);
        attempt.phase = 2; rejected++; rejectionReasons.merge(reason, 1L, Long::sum); attempts.remove(attempt.id);
        cancelTimer(attempt); attempt.response.completeExceptionally(new Failure(status, reason, cause)); lock.notifyAll();
    }
    private void settle(Attempt attempt, Throwable failure) {
        if (attempt.phase != 1) { if (conformance!=null) conformance.duplicate(attempt.key);return; } // duplicate/late callback after confirmed teardown
        attempt.phase = 3; outstandingBytes -= attempt.record.bytes(); attempts.remove(attempt.id);
        if (failure == null) succeeded++; else failed++;
        if (conformance!=null) conformance.settled(attempt.key,failure);
        cancelTimer(attempt);
        if (attempt.confirm) {
            if (!attempt.response.isDone() && System.nanoTime() >= attempt.deadline) {
                deadlines++;
                attempt.response.completeExceptionally(new Failure(503, "HTTP outcome deadline", null));
            }
            if (failure == null) attempt.response.complete(null);
            else attempt.response.completeExceptionally(new Failure(503, "client failed", failure));
        }
        lock.notifyAll();
    }
    private void expire(Attempt attempt) {
        synchronized (lock) {
            if (attempt.response.isDone()) return;
            deadlines++;
            if (conformance!=null) conformance.deadline(attempt.key);
            if (attempt.phase == 0) reject(attempt, "admission deadline", null, 503);
            else if (attempt.phase == 1) attempt.response.completeExceptionally(new Failure(503, "HTTP outcome deadline", null));
            // Admitted capacity belongs to the client outcome, not to this HTTP deadline.
        }
    }
    private static void cancelTimer(Attempt attempt) { if (attempt.timer != null) attempt.timer.cancel(false); }
    private static void closeWork(AutoCloseable work) { try { work.close(); } catch (Exception failure) { throw new IllegalStateException(failure); } }
    public void httpStatus(int status) { synchronized (lock) { httpStatuses.merge(Integer.toString(status), 1L, Long::sum); } }
    public void closeIntake() {
        synchronized (lock) {
            accepting = false;
            for (Attempt attempt : List.copyOf(attempts.values())) if (attempt.phase == 0) reject(attempt, "draining", null, 503);
        }
    }
    public Map<String, Object> state() {
        synchronized (lock) {
            Map<String, Object> result = new LinkedHashMap<>();
            long undecided = attempts.values().stream().filter(item -> item.phase == 0).count();
            long outstanding = attempts.values().stream().filter(item -> item.phase == 1).count();
            result.put("admission_candidates", candidates); result.put("admitted", admitted); result.put("admission_rejected", rejected); result.put("admission_undecided", undecided);
            result.put("client_succeeded", succeeded); result.put("client_failed", failed); result.put("abandoned_unknown", abandoned); result.put("outstanding", outstanding);
            result.put("outstanding_bytes", outstandingBytes); result.put("reserved_records", 0); result.put("reserved_bytes", 0);
            result.put("preparation_count", preparations); result.put("preparation_rejected", preparationRejected); result.put("deadline_observations", deadlines);
            result.put("http_status_counts", new TreeMap<>(httpStatuses)); result.put("admission_rejection_reasons", new TreeMap<>(rejectionReasons));
            result.put("configured_max_records", maxRecords); result.put("effective_max_records", maxRecords); result.put("configured_max_bytes", maxBytes); result.put("effective_max_bytes", maxBytes);
            result.put("configured_preparation_max_requests", maxPreparations); result.put("effective_preparation_max_requests", maxPreparations);
            result.put("http_outcome_timeout_ms", TimeUnit.NANOSECONDS.toMillis(timeoutNanos)); result.put("drain_timeout_ms", TimeUnit.NANOSECONDS.toMillis(drainNanos));
            result.put("teardown_timeout_ms", TimeUnit.NANOSECONDS.toMillis(teardownNanos));
            result.put("accepting", accepting); result.put("closing", closing); result.put("closed", closed); result.put("producer_teardown_confirmed", teardown && closed);
            result.put("drain_complete", drainComplete); result.put("drain_timed_out", drainTimedOut);
            return result;
        }
    }
    public Map<String, Object> drainEvidence() { synchronized (lock) { return Map.of("before", drainBefore, "after", drainAfter); } }
    @Override public void close() {
        long deadline = System.nanoTime() + drainNanos;
        synchronized (lock) {
            if (closing) return;
            closing = true;
            if (conformance!=null) conformance.draining();
            closeIntake(); drainBefore = state();
            while ((!attempts.isEmpty() || preparations != 0) && System.nanoTime() < deadline) {
                try { TimeUnit.NANOSECONDS.timedWait(lock, Math.max(1, deadline - System.nanoTime())); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
            }
            drainComplete = attempts.isEmpty() && preparations == 0;
            drainTimedOut = !drainComplete; teardown = true;
        }
        long teardownDeadline = System.nanoTime() + teardownNanos;
        dispatch.shutdownNow(); preparationExecutor.shutdownNow();
        // Kafka close(Duration.ZERO) can still join its sender without a timeout.
        // Never perform that join on the shutdown thread or manufacture confirmed teardown from a timed-out closer.
        CompletableFuture<Void> clientClose = new CompletableFuture<>();
        if (client == null) clientClose.complete(null);
        else {
            try {
                Thread.ofPlatform().daemon().name("pipeline-client-close").start(() -> {
                    try { client.close(); clientClose.complete(null); }
                    catch (Throwable failure) { clientClose.completeExceptionally(failure); }
                });
            } catch (Throwable failure) { clientClose.completeExceptionally(failure); }
        }
        boolean clientClosed = false, dispatchEnded = false;
        Throwable closeFailure = null;
        try {
            clientClose.get(Math.max(0, teardownDeadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            clientClosed = true;
        } catch (ExecutionException failure) { closeFailure = failure.getCause(); }
        catch (TimeoutException ignored) { /* Preserve credits: the SDK may still have live work. */ }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        try { dispatchEnded = dispatch.awaitTermination(Math.max(0, teardownDeadline - System.nanoTime()), TimeUnit.NANOSECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        boolean confirmed = clientClosed && dispatchEnded;
        synchronized (lock) {
            if (confirmed) {
                for (Attempt attempt : List.copyOf(attempts.values())) {
                    if (attempt.phase == 1) {
                        attempt.phase = 3; abandoned++; outstandingBytes -= attempt.record.bytes(); attempts.remove(attempt.id);
                        cancelTimer(attempt); attempt.response.completeExceptionally(new Failure(503, "abandoned/unknown after teardown", null));
                    }
                }
                closed = true;
                if (conformance!=null) conformance.teardownConfirmed();
            } else {
                drainComplete = false;
                for (Attempt attempt : attempts.values()) attempt.response.completeExceptionally(new Failure(503, "producer teardown unconfirmed", null));
            }
            drainAfter = state(); lock.notifyAll();
        }
        timer.shutdownNow();
        if (closeFailure instanceof RuntimeException failure) throw failure;
        if (closeFailure instanceof Error failure) throw failure;
        if (closeFailure != null) throw new IllegalStateException("producer teardown failed", closeFailure);
    }
    private static int envInt(String name, int fallback) { return Math.toIntExact(envLong(name, fallback)); }
    private static long envLong(String name, long fallback) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) return fallback;
        long parsed = Long.parseLong(value); if (parsed < 1) throw new IllegalArgumentException(name + " must be positive"); return parsed;
    }
}
