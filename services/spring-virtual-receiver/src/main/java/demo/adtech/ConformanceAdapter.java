package demo.adtech;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Opt-in bounded fixture transport; all accounting belongs to the attached real pipeline. */
public final class ConformanceAdapter implements OwnedPipeline.Client {
    private static final Set<String> FAULTS=Set.of("serialization_failure","producer_unavailable","admission_throw",
            "admission_failed_future","held_admission_then_success","held_delivery_then_success","delivery_failure_after_receipt",
            "response_deadline_then_late_success","backlog_record_limit","backlog_byte_limit","preparation_limit","final_slot_race",
            "drain_deadline_unsettled","confirm_ack_zero","held_admission_then_rejection","admission_deadline_then_late_receipt",
            "duplicate_completion_callback","source_mutation_after_receipt");
    private final Object lock=new Object(), commands=new Object();
    private final String mode;
    private final byte[] token;
    private OwnedPipeline pipeline;
    private String caseId, fault;
    private long eventSequence, fixtureDeadline, eventsDropped;
    private String deadlineKey;
    private boolean deadlineObserved, transportClosed;
    private CompletableFuture<Void> admissionBarrier;
    private final Set<String> setupKeys=ConcurrentHashMap.newKeySet();
    private final List<OwnedPipeline.Preparation> setupPreparations=new ArrayList<>();
    private final Map<String,Held> setupDelivery=new LinkedHashMap<>(), heldDelivery=new LinkedHashMap<>();
    private final Set<String> heldAdmission=new LinkedHashSet<>();
    private final Map<String,byte[]> sources=new LinkedHashMap<>();
    private final Set<String> committed=new HashSet<>();
    private final List<Map<String,Object>> events=new ArrayList<>(), records=new ArrayList<>();
    private Map<String,Object> baseline=Map.of(), mutation;
    private Consumer<Throwable> lastCompletion;
        private long setupBytes;
    private record Held(OwnedPipeline.Record record,Consumer<Throwable> completion,boolean captureDeferred) { }

    public static ConformanceAdapter fromEnvironment(String mode) {
        String enabled=System.getenv("BENCHMARK_CONFORMANCE"), token=System.getenv("BENCHMARK_CONFORMANCE_TOKEN");
        return "1".equals(enabled) && token!=null && !token.isEmpty()?new ConformanceAdapter(mode,token):null;
    }
    ConformanceAdapter(String mode,String token) {
        if (token.isEmpty() || token.getBytes(StandardCharsets.UTF_8).length>256) throw new IllegalArgumentException("bounded nonempty conformance token required");
        this.mode=mode;this.token=token.getBytes(StandardCharsets.UTF_8);
    }
    void bind(OwnedPipeline pipeline) { this.pipeline=pipeline;pipeline.installConformance(this); }
    public boolean authorized(List<String> headers) {
        return headers!=null && headers.size()==1 && headers.getFirst().length()<=256
                && MessageDigest.isEqual(token,headers.getFirst().getBytes(StandardCharsets.UTF_8));
    }
    boolean fixture(String key) { return !setupKeys.contains(key); }
    void candidate(String key,long deadline) {
        if (!fixture(key)) return;
        synchronized(lock) { fixtureDeadline=deadline;deadlineKey=key; }
    }
    void beforeSerialization(String key) {
        if (fixture(key) && is("serialization_failure")) throw new IllegalStateException("injected serialization failure");
    }
    void source(String key,byte[] bytes) {
        if (fixture(key) && is("source_mutation_after_receipt")) synchronized(lock) {
            if (sources.size()>=4) throw new IllegalStateException("bounded fixture source ledger full");
            sources.put(key,bytes);
        }
    }
    CompletionStage<Void> admission(OwnedPipeline.Record record) {
        if (!fixture(record.key())) return CompletableFuture.completedFuture(null);
        synchronized(lock) {
            if (fault==null) return CompletableFuture.failedFuture(new OwnedPipeline.Failure(503,"conformance fixture not configured",null));
            switch (fault) {
                case "producer_unavailable" -> { event("producer_unavailable",record.key());return CompletableFuture.failedFuture(new OwnedPipeline.Failure(503,"producer unavailable",null)); }
                case "admission_throw" -> { event("admission_throws",record.key());throw new OwnedPipeline.Failure(503,"injected admission throw",null); }
                case "admission_failed_future" -> { event("admission_returns_failed_future",record.key());return CompletableFuture.failedFuture(new OwnedPipeline.Failure(503,"injected failed admission future",null)); }
                case "held_admission_then_success","held_admission_then_rejection","admission_deadline_then_late_receipt","final_slot_race" -> {
                    if (heldAdmission.size()>=2) throw new OwnedPipeline.Failure(503,"fixture contender bound",null);
                    heldAdmission.add(record.key());event("admission_held",record.key());return admissionBarrier;
                }
                default -> { return CompletableFuture.completedFuture(null); }
            }
        }
    }
    void committed(OwnedPipeline.Record record) {
        if (!fixture(record.key())) return;
        synchronized(lock) { committed.add(record.key());heldAdmission.remove(record.key());event("receipt_committed",record.key()); }
    }
    void rejected(String key) { if (fixture(key)) synchronized(lock) { heldAdmission.remove(key);event("admission_rejected",key); } }
    void lateReceipt(String key) { if (fixture(key)) synchronized(lock) { heldAdmission.remove(key);event("late_receipt_fenced",key); } }
    void deadline(String key) { if (fixture(key)) synchronized(lock) { if (!deadlineObserved) { deadlineObserved=true;event("deadline_observed",key); } } }
    void settled(String key,Throwable failure) { if (fixture(key)) synchronized(lock) { event(failure==null?"client_succeeded":"client_failed",key); } }
    void duplicate(String key) { if (fixture(key)) synchronized(lock) { event("duplicate_completion",key); } }
    void draining() { synchronized(lock) { event("drain_started",null); } }
    void teardownConfirmed() { synchronized(lock) { event("teardown_confirmed",null); } }
    private boolean is(String value) { synchronized(lock) { return value.equals(fault); } }
    private void event(String name,String key) {
        if (events.size()>=256) { eventsDropped++;return; }
        var row=RuntimeEvidence.object("sequence",++eventSequence,"name",name);
        if (key!=null) row.put("key",key);events.add(row);
    }
    @Override public void send(OwnedPipeline.Record record,Consumer<Throwable> completion) {
        boolean immediate;
        synchronized(lock) {
            if (transportClosed) throw new IllegalStateException("conformance transport closed");
            if (setupKeys.contains(record.key())) {
                setupDelivery.put(record.key(),new Held(record,completion,false));return;
            }
            if (heldDelivery.size()>=4) throw new IllegalStateException("bounded fixture delivery ledger full");
            boolean deferred="source_mutation_after_receipt".equals(fault);
            if (!deferred) capture(record);
            immediate="confirm_ack_zero".equals(fault);
            if (!immediate) { heldDelivery.put(record.key(),new Held(record,completion,deferred));event("delivery_held",record.key()); }
        }
        if (immediate) completion.accept(null);
    }
    private void capture(OwnedPipeline.Record record) {
        if (records.size()>=4) throw new IllegalStateException("bounded fixture record ledger full");
        records.add(RuntimeEvidence.object("topic",record.topic(),"key_base64",Base64.getEncoder().encodeToString(record.keyBytes()),
                "value_base64",Base64.getEncoder().encodeToString(record.value())));
    }
    @Override public void close() { synchronized(lock) { transportClosed=true;heldDelivery.clear();setupDelivery.clear(); } }

    public Map<String,Object> command(Map<String,Object> command) throws Exception {
        if (command==null) throw new IllegalArgumentException("conformance command object required");
        String action=string(command,"action",32);
        if (!Set.of("action","case_id","fault").containsAll(command.keySet())) throw new IllegalArgumentException("unknown conformance command member");
        synchronized(commands) {
            switch(action) {
                case "configure" -> configure(string(command,"case_id",128),string(command,"fault",64));
                case "release_admission" -> releaseAdmission(false);
                case "reject_admission" -> releaseAdmission(true);
                case "release_delivery" -> releaseDelivery(false);
                case "fail_delivery" -> releaseDelivery(true);
                case "mutate_source" -> mutate();
                case "duplicate_completion" -> duplicateCompletion();
                case "cleanup" -> cleanup();
                case "drain" -> pipeline.close();
                default -> throw new IllegalArgumentException("unknown conformance action");
            }
        }
        return snapshot();
    }
    private static String string(Map<String,Object> input,String key,int limit) {
        Object value=input.get(key);if (!(value instanceof String text) || text.isEmpty() || text.length()>limit) throw new IllegalArgumentException("bounded "+key+" required");return text;
    }
    private void configure(String id,String selected) throws Exception {
        if (!FAULTS.contains(selected)) throw new IllegalArgumentException("unknown conformance fault");
        Map<String,Object> state=pipeline.state();
        if (((Number)state.get("outstanding")).longValue()!=0 || ((Number)state.get("admission_undecided")).longValue()!=0
                || ((Number)state.get("preparation_count")).intValue()!=0 || Boolean.TRUE.equals(state.get("closing"))) throw new IllegalStateException("cleanup previous live fixture before configure");
        synchronized(lock) {
            caseId=id;fault=selected;eventsDropped=0;fixtureDeadline=0;deadlineKey=null;deadlineObserved=false;
            events.clear();records.clear();sources.clear();committed.clear();heldAdmission.clear();heldDelivery.clear();setupDelivery.clear();
            setupKeys.clear();setupBytes=0;setupPreparations.clear();mutation=null;lastCompletion=null;
            admissionBarrier=new CompletableFuture<>();
        }
        boolean httpOnly="http-only".equals(mode);
        if (selected.equals("preparation_limit")) {
            requireDefaults(state);
            for (int i=0;i<128;i++) { var lease=pipeline.prepare();if (lease==null) throw new IllegalStateException("real preparation setup rejected");synchronized(lock) { setupPreparations.add(lease); } }
        } else if (!httpOnly && (selected.equals("backlog_record_limit") || selected.equals("final_slot_race") || selected.equals("backlog_byte_limit"))) {
            requireDefaults(state);
            if (selected.equals("backlog_byte_limit")) {
                long remaining=16777216L-1;
                while (remaining>0) { int chunk=(int)Math.min(65536,remaining);setupRecord(chunk);remaining-=chunk; }
            } else for (int i=0;i<(selected.equals("final_slot_race")?1023:1024);i++) setupRecord(32);
            waitUntil(() -> { synchronized(lock) { return setupDelivery.size()==setupKeys.size(); } },"setup dispatch");
        }
        waitUntil(() -> ((Number)pipeline.state().get("preparation_count")).intValue()==setupPreparations.size(),"setup preparation release");
        var actualBaseline=pipeline.state();synchronized(lock) { baseline=actualBaseline; }
    }
    private static void requireDefaults(Map<String,Object> state) {
        if (((Number)state.get("effective_max_records")).intValue()!=1024 || ((Number)state.get("effective_max_bytes")).longValue()!=16777216
                || ((Number)state.get("effective_preparation_max_requests")).intValue()!=128) throw new IllegalArgumentException("saturation fixtures require real default 1024/16MiB/128 bounds");
    }
    private void setupRecord(int encodedBytes) throws Exception {
        String key="conformance-setup-"+setupKeys.size();byte[] value=new byte[encodedBytes-key.getBytes(StandardCharsets.UTF_8).length];
        setupKeys.add(key);pipeline.publish(key,()->value,false,null).get(10,TimeUnit.SECONDS);setupBytes+=encodedBytes;
    }
    private void releaseAdmission(boolean reject) {
        CompletableFuture<Void> barrier;List<String> keys;
        synchronized(lock) {
            if ("final_slot_race".equals(fault) && heldAdmission.size()!=2) throw new IllegalStateException("observe two actual contenders before race release");
            barrier=admissionBarrier;keys=List.copyOf(heldAdmission);
            for (String key:keys) event("admission_released",key);
            heldAdmission.clear();
        }
        if (barrier==null) throw new IllegalStateException("no admission barrier configured");
        if (reject) barrier.completeExceptionally(new OwnedPipeline.Failure(503,"deferred admission rejection",null));else barrier.complete(null);
    }
    private void releaseDelivery(boolean fail) {
        List<Held> held;
        synchronized(lock) { held=List.copyOf(heldDelivery.values());heldDelivery.clear(); }
        for (Held item:held) {
            synchronized(lock) {
                if (item.captureDeferred()) capture(item.record());
                lastCompletion=item.completion();event("delivery_released",item.record().key());
            }
            item.completion().accept(fail?new IllegalStateException("injected client failure"):null);
        }
    }
    private void mutate() {
        synchronized(lock) {
            if (!"source_mutation_after_receipt".equals(fault) || sources.isEmpty()) throw new IllegalStateException("no fixture caller source retained");
            String key=sources.keySet().iterator().next();if (!committed.contains(key)) throw new IllegalStateException("observe owned receipt before source reuse");
            byte[] source=sources.get(key);String before=Base64.getEncoder().encodeToString(source);
            Arrays.fill(source,(byte)'!');
            mutation=RuntimeEvidence.object("performed",true,"before_value_base64",before,"after_value_base64",Base64.getEncoder().encodeToString(source),
                    "evidence","mutated the actual serializer-returned caller byte array after OwnedPipeline.Record cloned it; configured topic and key are immutable Java Strings retained by the receipt");
            event("source_mutated",key);
        }
    }
    private void duplicateCompletion() {
        Consumer<Throwable> callback;synchronized(lock) { callback=lastCompletion; }
        if (callback==null) throw new IllegalStateException("release actual client outcome before duplicate callback");callback.accept(null);
    }
    private void cleanup() throws Exception {
        // Setup blockers remain held throughout fixture observations and settle only here.
        CompletableFuture<Void> barrier;synchronized(lock) { barrier=admissionBarrier; }
        if (barrier!=null && !barrier.isDone()) barrier.completeExceptionally(new OwnedPipeline.Failure(503,"fixture cleanup",null));
        releaseDelivery(false);
        List<Held> blockers;synchronized(lock) { blockers=List.copyOf(setupDelivery.values());setupDelivery.clear();heldAdmission.clear(); }
        blockers.forEach(item->item.completion().accept(null));
        List<OwnedPipeline.Preparation> preparations;synchronized(lock) { preparations=List.copyOf(setupPreparations);setupPreparations.clear(); }
        preparations.forEach(OwnedPipeline.Preparation::close);
        waitUntil(() -> { var state=pipeline.state();return ((Number)state.get("outstanding")).longValue()==0
                && ((Number)state.get("admission_undecided")).longValue()==0 && ((Number)state.get("preparation_count")).intValue()==0; },"cleanup actual accounting");
    }
    private static void waitUntil(java.util.function.BooleanSupplier condition,String description) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime()>=deadline) throw new TimeoutException(description+" did not finish");Thread.sleep(1);
        }
    }
    public Map<String,Object> snapshot() {
        Map<String,Object> result;
        synchronized(lock) {
            boolean elapsed=fixtureDeadline>0 && System.nanoTime()>=fixtureDeadline;
            boolean deadlineCase=fault!=null && Set.of("response_deadline_then_late_success","admission_deadline_then_late_receipt").contains(fault);
            if (elapsed && deadlineCase && !deadlineObserved) { deadlineObserved=true;event("deadline_observed",deadlineKey); }
            result=RuntimeEvidence.object("enabled",true,"case_id",caseId,"fault",fault,"events",List.copyOf(events),"baseline",baseline,
                    "held_admission",heldAdmission.size(),"held_delivery",heldDelivery.size(),"records",List.copyOf(records),
                    "setup",RuntimeEvidence.object("records",setupKeys.size(),"bytes",setupBytes,"preparations",setupPreparations.size()),
                    "mutation",mutation,"deadline_elapsed",deadlineCase?elapsed:null,"events_dropped",eventsDropped);
        }
        result.put("state",pipeline.state());result.put("drain",Boolean.TRUE.equals(pipeline.state().get("closing"))?pipeline.drainEvidence():null);return result;
    }
    public Map<String,Object> transportMetadata(Map<String,Object> noSdk) {
        noSdk.put("conformance_transport",true);noSdk.put("evidence","controlled opt-in fault transport; no Kafka SDK producer constructed; healthy SDK integration requires adapters disabled");return noSdk;
    }
}
