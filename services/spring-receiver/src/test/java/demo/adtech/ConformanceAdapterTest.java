package demo.adtech;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class ConformanceAdapterTest {
    static final byte[] VALUE="{\"id\":\"req-1-0\",\"site\":{},\"device\":{\"lmt\":0}}".getBytes(StandardCharsets.UTF_8);
    record Fixture(ConformanceAdapter adapter,OwnedPipeline pipeline) implements AutoCloseable {
        public void close() throws Exception { if (!Boolean.TRUE.equals(pipeline.state().get("closing"))) adapter.command(Map.of("action","cleanup"));pipeline.close(); }
    }
    static Fixture fixture(String mode,long outcome,long drain) {
        var adapter=new ConformanceAdapter(mode,"test-token");
        var pipeline=new OwnedPipeline("actual-topic",mode.equals("http-only")?null:adapter,1,1024,16777216,128,Duration.ofMillis(outcome),Duration.ofMillis(drain));
        adapter.bind(pipeline);return new Fixture(adapter,pipeline);
    }
    static void configure(Fixture fixture,String fault) throws Exception { fixture.adapter.command(Map.of("action","configure","case_id","test-"+fault,"fault",fault)); }
    static long count(Fixture fixture,String key) { return ((Number)fixture.pipeline.state().get(key)).longValue(); }
    static long delta(Fixture fixture,String key) { return count(fixture,key)-((Number)((Map<?,?>)fixture.adapter.snapshot().get("baseline")).get(key)).longValue(); }
    static void waitFor(java.util.function.BooleanSupplier ready) throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while (!ready.getAsBoolean()) { if (System.nanoTime()>until) fail("real milestone not observed");Thread.sleep(1); }
    }
    static void failure(CompletableFuture<Void> outcome,int status) throws Exception {
        var failure=assertThrows(ExecutionException.class,()->outcome.get(5,TimeUnit.SECONDS));
        assertInstanceOf(OwnedPipeline.Failure.class,failure.getCause());assertEquals(status,((OwnedPipeline.Failure)failure.getCause()).status);
    }
    static int held(Fixture fixture,String key) { return ((Number)fixture.adapter.snapshot().get(key)).intValue(); }
    static boolean event(Fixture fixture,String name) { return ((List<?>)fixture.adapter.snapshot().get("events")).stream().anyMatch(x->name.equals(((Map<?,?>)x).get("name"))); }

    @Test void authAndUnconfiguredSnapshotsDoNotFabricateWork() throws Exception {
        try (var f=fixture("enqueue",1000,20)) {
            assertFalse(f.adapter.authorized(null));assertFalse(f.adapter.authorized(List.of("wrong")));
            assertFalse(f.adapter.authorized(List.of("test-token","test-token")));assertTrue(f.adapter.authorized(List.of("test-token")));
            var state=f.pipeline.state();assertEquals(state,f.adapter.snapshot().get("state"));assertEquals(0,count(f,"admission_candidates"));
            assertEquals(0,held(f,"held_delivery"));assertNull(f.adapter.snapshot().get("deadline_elapsed"));
        }
    }
    @Test void allFourPrecommitFailuresUseActualCandidateRejectionInBothModes() throws Exception {
        for (boolean confirm:List.of(false,true)) for (String fault:List.of("serialization_failure","producer_unavailable","admission_throw","admission_failed_future")) {
            try (var f=fixture(confirm?"confirm":"enqueue",1000,20)) {
                configure(f,fault);var outcome=f.pipeline.publish("req-1-0",()->VALUE,confirm,null);
                failure(outcome,fault.equals("serialization_failure")?500:503);
                assertEquals(1,delta(f,"admission_candidates"));assertEquals(1,delta(f,"admission_rejected"));
                assertEquals(0,delta(f,"admitted"));assertEquals(0,held(f,"held_delivery"));assertTrue(((List<?>)f.adapter.snapshot().get("records")).isEmpty());
            }
        }
    }
    @Test void heldAdmissionAndDeliveryKeepTheirSeparateResponseBoundaries() throws Exception {
        for (boolean confirm:List.of(false,true)) for (String fault:List.of("held_admission_then_success","held_delivery_then_success","delivery_failure_after_receipt")) {
            try (var f=fixture(confirm?"confirm":"enqueue",2000,20)) {
                configure(f,fault);var outcome=f.pipeline.publish("req-1-0",()->VALUE,confirm,null);
                if (fault.equals("held_admission_then_success")) {
                    waitFor(()->held(f,"held_admission")==1);assertFalse(outcome.isDone());assertEquals(1,delta(f,"admission_undecided"));
                    f.adapter.command(Map.of("action","release_admission"));
                }
                waitFor(()->held(f,"held_delivery")==1);assertEquals(1,delta(f,"admitted"));assertEquals(1,delta(f,"outstanding"));
                assertEquals(confirm,!outcome.isDone());
                f.adapter.command(Map.of("action",fault.equals("delivery_failure_after_receipt")?"fail_delivery":"release_delivery"));
                if (confirm && fault.equals("delivery_failure_after_receipt")) failure(outcome,503);else outcome.get(5,TimeUnit.SECONDS);
                assertEquals(1,delta(f,fault.equals("delivery_failure_after_receipt")?"client_failed":"client_succeeded"));assertEquals(0,delta(f,"outstanding"));
            }
        }
    }
    @Test void deferredRejectionAndActualDeadlineFenceDoNotCreateReceipts() throws Exception {
        for (String fault:List.of("held_admission_then_rejection","admission_deadline_then_late_receipt")) {
            try (var f=fixture("enqueue",100,20)) {
                configure(f,fault);var outcome=f.pipeline.publish("req-1-0",()->VALUE,false,null);
                waitFor(()->held(f,"held_admission")==1);assertFalse(outcome.isDone());
                if (fault.equals("held_admission_then_rejection")) f.adapter.command(Map.of("action","reject_admission"));
                failure(outcome,503);assertEquals(1,delta(f,"admission_rejected"));assertEquals(0,delta(f,"admitted"));
                if (fault.equals("admission_deadline_then_late_receipt")) {
                    assertTrue((Boolean)f.adapter.snapshot().get("deadline_elapsed"));assertEquals(1,count(f,"preparation_count"));
                    f.adapter.command(Map.of("action","release_admission"));waitFor(()->event(f,"late_receipt_fenced"));
                    waitFor(()->count(f,"preparation_count")==0);
                }
                assertTrue(((List<?>)f.adapter.snapshot().get("records")).isEmpty());
            }
        }
    }
    @Test void lateSuccessUsesActualDeadlineWithoutRevokingEnqueueAcceptance() throws Exception {
        for (boolean confirm:List.of(false,true)) try (var f=fixture(confirm?"confirm":"enqueue",100,20)) {
            configure(f,"response_deadline_then_late_success");var outcome=f.pipeline.publish("req-1-0",()->VALUE,confirm,null);
            waitFor(()->held(f,"held_delivery")==1);
            if (confirm) failure(outcome,503);else outcome.get(5,TimeUnit.SECONDS);
            waitFor(()->Boolean.TRUE.equals(f.adapter.snapshot().get("deadline_elapsed")));
            assertEquals(1,delta(f,"outstanding"));f.adapter.command(Map.of("action","release_delivery"));
            assertEquals(1,delta(f,"client_succeeded"));assertEquals(confirm,outcome.isCompletedExceptionally());assertTrue(event(f,"deadline_observed"));
        }
    }
    @Test void realDefaultCountAndByteReceiptsStayHeldUntilCleanup() throws Exception {
        for (String fault:List.of("backlog_record_limit","backlog_byte_limit")) try (var f=fixture("enqueue",2000,20)) {
            configure(f,fault);int expected=fault.equals("backlog_record_limit")?1024:256;
            assertEquals(expected,count(f,"outstanding"));assertEquals(fault.equals("backlog_record_limit")?32768:16777215,count(f,"outstanding_bytes"));
            failure(f.pipeline.publish("req-1-0",()->VALUE,false,null),503);
            f.adapter.command(Map.of("action","release_delivery"));assertEquals(expected,count(f,"outstanding"));assertEquals(0,delta(f,"client_succeeded"));
            f.adapter.command(Map.of("action","cleanup"));assertEquals(0,count(f,"outstanding"));assertEquals(expected,delta(f,"client_succeeded"));
        }
    }
    @Test void realPreparationLimitAppliesToEveryMode() throws Exception {
        for (String mode:List.of("http-only","enqueue","confirm")) try (var f=fixture(mode,1000,20)) {
            configure(f,"preparation_limit");assertEquals(128,count(f,"preparation_count"));assertNull(f.pipeline.prepare());
            assertEquals(1,delta(f,"preparation_rejected"));assertEquals(0,delta(f,"admission_candidates"));
            f.adapter.command(Map.of("action","cleanup"));assertEquals(0,count(f,"preparation_count"));
        }
    }
    @Test void commonBarrierRacesTwoActualCandidatesForUnchosenLastSlot() throws Exception {
        try (var f=fixture("enqueue",5000,20)) {
            configure(f,"final_slot_race");assertEquals(1023,count(f,"outstanding"));
            var one=f.pipeline.publish("contender-one",()->VALUE,false,null);var two=f.pipeline.publish("contender-two",()->VALUE,false,null);
            waitFor(()->held(f,"held_admission")==2);assertFalse(one.isDone());assertFalse(two.isDone());
            f.adapter.command(Map.of("action","release_admission"));
            CompletableFuture.allOf(one.handle((x,e)->null),two.handle((x,e)->null)).get(5,TimeUnit.SECONDS);
            assertNotEquals(one.isCompletedExceptionally(),two.isCompletedExceptionally());waitFor(()->held(f,"held_delivery")==1);
            assertEquals(2,delta(f,"admission_candidates"));assertEquals(1,delta(f,"admitted"));assertEquals(1,delta(f,"admission_rejected"));
            f.adapter.command(Map.of("action","release_delivery"));assertEquals(1023,count(f,"outstanding"));assertEquals(1,delta(f,"client_succeeded"));
            var records=(List<?>)f.adapter.snapshot().get("records");assertEquals(1,records.size());
            String winner=new String(Base64.getDecoder().decode((String)((Map<?,?>)records.getFirst()).get("key_base64")),StandardCharsets.UTF_8);
            assertEquals(one.isCompletedExceptionally()?"contender-two":"contender-one",winner);
        }
    }
    @Test void actualCallerBytesMutateBeforeDeferredDispatchButOwnedRecordDoesNot() throws Exception {
        try (var f=fixture("enqueue",1000,20)) {
            configure(f,"source_mutation_after_receipt");byte[] source=VALUE.clone();
            f.pipeline.publish("req-1-0",()->source,false,null).get(5,TimeUnit.SECONDS);waitFor(()->held(f,"held_delivery")==1);
            assertTrue(((List<?>)f.adapter.snapshot().get("records")).isEmpty());f.adapter.command(Map.of("action","mutate_source"));
            assertFalse(Arrays.equals(source,VALUE));var mutation=(Map<?,?>)f.adapter.snapshot().get("mutation");assertEquals(true,mutation.get("performed"));
            assertArrayEquals(VALUE,Base64.getDecoder().decode((String)mutation.get("before_value_base64")));
            f.adapter.command(Map.of("action","release_delivery"));var record=(Map<?,?>)((List<?>)f.adapter.snapshot().get("records")).getFirst();
            assertArrayEquals(VALUE,Base64.getDecoder().decode((String)record.get("value_base64")));assertEquals("actual-topic",record.get("topic"));
        }
    }
    @Test void duplicateCallbackReachesActualSettlementFenceAndDoesNotDoubleRelease() throws Exception {
        try (var f=fixture("enqueue",1000,20)) {
            configure(f,"duplicate_completion_callback");f.pipeline.publish("req-1-0",()->VALUE,false,null).get(5,TimeUnit.SECONDS);waitFor(()->held(f,"held_delivery")==1);
            f.adapter.command(Map.of("action","release_delivery"));var before=f.pipeline.state();f.adapter.command(Map.of("action","duplicate_completion"));
            assertEquals(before,f.pipeline.state());assertTrue(event(f,"duplicate_completion"));
        }
    }
    @Test void realDrainRetainsThenAbandonsOnlyAfterConfirmedTransportTeardown() throws Exception {
        try (var f=fixture("confirm",1000,30)) {
            configure(f,"drain_deadline_unsettled");var outcome=f.pipeline.publish("req-1-0",()->VALUE,true,null);waitFor(()->held(f,"held_delivery")==1);
            assertEquals(1,delta(f,"outstanding"));assertEquals(0,delta(f,"abandoned_unknown"));f.adapter.command(Map.of("action","drain"));failure(outcome,503);
            assertEquals(1,delta(f,"abandoned_unknown"));assertEquals(0,delta(f,"outstanding"));assertEquals(true,f.pipeline.state().get("producer_teardown_confirmed"));
            assertEquals(false,f.pipeline.state().get("drain_complete"));assertTrue(event(f,"drain_started"));assertTrue(event(f,"teardown_confirmed"));
        }
    }
    @Test void httpOnlyBypassesPipelineFaultSetupAndAckZeroEnqueueActuallySettles() throws Exception {
        try (var f=fixture("http-only",1000,20)) {
            for (String fault:List.of("serialization_failure","producer_unavailable","admission_throw","admission_failed_future","held_admission_then_success",
                    "held_delivery_then_success","delivery_failure_after_receipt","response_deadline_then_late_success","backlog_record_limit","backlog_byte_limit",
                    "final_slot_race","drain_deadline_unsettled","confirm_ack_zero","held_admission_then_rejection","admission_deadline_then_late_receipt",
                    "duplicate_completion_callback","source_mutation_after_receipt")) {
                configure(f,fault);assertEquals(0,delta(f,"admission_candidates"));assertEquals(0,count(f,"outstanding"));assertEquals(0,count(f,"preparation_count"));
            }
        }
        try (var f=fixture("enqueue",1000,20)) {
            configure(f,"confirm_ack_zero");f.pipeline.publish("req-1-0",()->VALUE,false,null).get(5,TimeUnit.SECONDS);waitFor(()->delta(f,"client_succeeded")==1);
            assertEquals(1,delta(f,"admitted"));assertEquals(0,delta(f,"outstanding"));
        }
    }
}
