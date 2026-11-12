package demo.adtech;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@io.quarkus.test.junit.QuarkusTest
@io.quarkus.test.junit.TestProfile(ConformanceHttpTest.FaultProfile.class)
@EnabledIfEnvironmentVariable(named="BENCHMARK_CONFORMANCE",matches="1")
class ConformanceHttpTest {
    public static class FaultProfile implements io.quarkus.test.junit.QuarkusTestProfile {
        public java.util.Map<String,String> getConfigOverrides() { return java.util.Map.of("benchmark.delivery.mode","enqueue","quarkus.kafka.devservices.enabled","false"); }
    }
    static final String TOKEN="java-fault-http-token";
    static final String BODY="{\"id\":\"req-1-0\",\"site\":{\"id\":\"site-1\",\"domain\":\"cnn.com\"},\"device\":{\"ip\":\"1.2.2.1\",\"ua\":\"Mozilla/5.0 (Macintosh; Intel Mac OS X 14_0)\",\"lmt\":0}}";
    HttpRequest request(String path) { return HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+io.restassured.RestAssured.port+path)).timeout(Duration.ofSeconds(10)).GET().build(); }
    com.fasterxml.jackson.databind.JsonNode read(HttpClient client) throws Exception {
        var response=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+io.restassured.RestAssured.port+"/benchmark/conformance")).header("X-Conformance-Token",TOKEN).GET().build(),HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200,response.statusCode(),new String(response.body(),StandardCharsets.UTF_8));return ContractFixtures.JSON.readTree(response.body());
    }
    com.fasterxml.jackson.databind.JsonNode action(HttpClient client,String action,String fault) throws Exception {
        String body=fault==null?"{\"action\":\""+action+"\"}":"{\"action\":\"configure\",\"case_id\":\"wire-"+fault+"\",\"fault\":\""+fault+"\"}";
        var response=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+io.restassured.RestAssured.port+"/benchmark/conformance"))
                .header("X-Conformance-Token",TOKEN).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200,response.statusCode(),new String(response.body(),StandardCharsets.UTF_8));return ContractFixtures.JSON.readTree(response.body());
    }
    CompletableFuture<HttpResponse<byte[]>> bid(HttpClient client,String body) {
        return client.sendAsync(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+io.restassured.RestAssured.port+"/bid-request"))
                .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofByteArray());
    }
    com.fasterxml.jackson.databind.JsonNode waitFor(HttpClient client,String field,int value) throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        do { var snapshot=read(client);if (snapshot.get(field).asInt()==value) return snapshot;Thread.sleep(2); } while(System.nanoTime()<until);
        fail("HTTP milestone "+field+"="+value+" missing");return null;
    }
    @Test void unauthorizedMalformedCommandsAre404WithoutBusinessAccounting() throws Exception {
        try (var client=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
            var before=read(client).get("state");
            for (boolean wrong:List.of(false,true)) {
                var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+io.restassured.RestAssured.port+"/benchmark/conformance"));
                if (wrong) builder.header("X-Conformance-Token","wrong");
                assertEquals(404,client.send(builder.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{")).build(),HttpResponse.BodyHandlers.discarding()).statusCode());
            }
            var duplicate=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+io.restassured.RestAssured.port+"/benchmark/conformance"))
                    .header("X-Conformance-Token",TOKEN).header("X-Conformance-Token",TOKEN).GET().build();
            assertEquals(404,client.send(duplicate,HttpResponse.BodyHandlers.discarding()).statusCode());
            assertEquals(before,read(client).get("state"));
        }
    }
    @Test void actualWireHandlerRejectsPrecommitFaultsAndKeepsHeldEnqueuePendingUntilReceipt() throws Exception {
        try (var client=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
            for (String fault:List.of("serialization_failure","producer_unavailable","admission_throw","admission_failed_future")) {
                action(client,"configure",fault);
                assertEquals(fault.equals("serialization_failure")?500:503,bid(client,BODY).get(5,TimeUnit.SECONDS).statusCode());
                var snapshot=read(client);assertEquals(1,snapshot.get("state").get("admission_candidates").asLong()-snapshot.get("baseline").get("admission_candidates").asLong());
                assertTrue(snapshot.get("records").isEmpty());action(client,"cleanup",null);
            }
            action(client,"configure","held_admission_then_success");var request=bid(client,BODY);
            waitFor(client,"held_admission",1);assertFalse(request.isDone());action(client,"release_admission",null);waitFor(client,"held_delivery",1);
            assertEquals(200,request.get(5,TimeUnit.SECONDS).statusCode());assertEquals(1,read(client).get("state").get("outstanding").asInt());
            action(client,"release_delivery",null);action(client,"cleanup",null);
        }
    }
    @Test void actualWireSourceMutationUsesUnchangedOwnedCanonicalRecord() throws Exception {
        try (var client=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
            action(client,"configure","source_mutation_after_receipt");assertEquals(200,bid(client,BODY).get(5,TimeUnit.SECONDS).statusCode());waitFor(client,"held_delivery",1);
            assertTrue(read(client).get("records").isEmpty());var changed=action(client,"mutate_source",null);assertTrue(changed.get("mutation").get("performed").asBoolean());
            assertNotEquals(changed.get("mutation").get("before_value_base64").asText(),changed.get("mutation").get("after_value_base64").asText());
            var released=action(client,"release_delivery",null);assertEquals(1,released.get("records").size());
            var record=released.get("records").get(0);assertEquals("req-1-0",new String(Base64.getDecoder().decode(record.get("key_base64").asText()),StandardCharsets.UTF_8));
            var input=ReceiverContract.parse(BODY.getBytes(StandardCharsets.UTF_8));
            assertEquals(ContractFixtures.JSON.readTree(ContractFixtures.JSON.writeValueAsBytes(input)),ContractFixtures.JSON.readTree(Base64.getDecoder().decode(record.get("value_base64").asText())));
            action(client,"duplicate_completion",null);action(client,"cleanup",null);
        }
    }
    @Test void actualWireDefaultFinalSlotRaceObservesItsWinner() throws Exception {
        try (var client=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
            var setup=action(client,"configure","final_slot_race");assertEquals(1023,setup.get("state").get("outstanding").asInt());
            var one=bid(client,BODY.replace("req-1-0","contender-one"));var two=bid(client,BODY.replace("req-1-0","contender-two"));
            waitFor(client,"held_admission",2);assertFalse(one.isDone());assertFalse(two.isDone());action(client,"release_admission",null);
            int first=one.get(5,TimeUnit.SECONDS).statusCode(),second=two.get(5,TimeUnit.SECONDS).statusCode();
            assertEquals(Set.of(200,503),Set.of(first,second));waitFor(client,"held_delivery",1);var state=action(client,"release_delivery",null);
            assertEquals(1023,state.get("state").get("outstanding").asInt());String winner=new String(Base64.getDecoder().decode(state.get("records").get(0).get("key_base64").asText()),StandardCharsets.UTF_8);
            assertEquals(first==200?"contender-one":"contender-two",winner);action(client,"cleanup",null);
        }
    }
}
