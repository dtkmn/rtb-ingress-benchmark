package demo.adtech;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.Map;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;
import static org.hamcrest.Matchers.is;

@QuarkusTest
@TestProfile(PreparationHttpTest.HttpOnlyProfile.class)
class PreparationHttpTest {
    public static class HttpOnlyProfile implements QuarkusTestProfile {
        @Override public Map<String,String> getConfigOverrides() {
            return Map.of("benchmark.delivery.mode", "http-only", "quarkus.kafka.devservices.enabled", "false", "quarkus.otel.enabled", "false");
        }
    }
    @Inject BidPublisher publisher;
    @Test void realHttpGatePrecedesJsonAndReleasesRejectedBodies() {
        ArrayList<OwnedPipeline.Preparation> leases = new ArrayList<>();
        try {
            for (int index=0; index<128; index++) leases.add(publisher.pipeline().prepare());
            long rejected = ((Number) publisher.pipeline().state().get("preparation_rejected")).longValue();
            given().contentType("text/plain").body("{").post("/bid-request").then().statusCode(415);
            assertEquals(rejected, ((Number) publisher.pipeline().state().get("preparation_rejected")).longValue());
            given().contentType("application/json").body("{").post("/bid-request").then().statusCode(503);
        } finally { leases.forEach(OwnedPipeline.Preparation::close); }
        given().contentType("application/json").body("{").post("/bid-request").then().statusCode(400);
        given().contentType("application/json").body("").post("/bid-request").then().statusCode(400);
        assertEquals(0, publisher.pipeline().state().get("preparation_count"));
        assertEquals(1L, publisher.pipeline().state().get("preparation_rejected"));
    }
    @Test void unknownLengthStreamIsBoundedByActualBytes() throws Exception {
        java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder().version(java.net.http.HttpClient.Version.HTTP_1_1).build();
        String json="{\"id\":\"k\",\"site\":{},\"device\":{}}";
        for (int length : new int[]{65536,65537}) {
            byte[] body=(json+" ".repeat(length-json.length())).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            java.net.http.HttpRequest request=java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:"+io.restassured.RestAssured.port+"/bid-request"))
                    .header("Content-Type","application/json").POST(java.net.http.HttpRequest.BodyPublishers.ofInputStream(() -> new java.io.ByteArrayInputStream(body))).build();
            assertEquals(length==65536?200:413, client.send(request, java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode());
        }
        assertEquals(0,publisher.pipeline().state().get("preparation_count"));
    }
    @Test void realHttpBodyHandlerEnforcesExactByteBoundaryAndNoKafkaCandidates() {
        String json="{\"id\":\"k\",\"site\":{},\"device\":{}}";
        for (int length : new int[]{65536,65537}) given().contentType("application/json")
                .body(json+" ".repeat(length-json.length())).post("/bid-request").then().statusCode(length==65536?200:413);
        given().get("/benchmark/state").then().statusCode(200).body("admission_candidates", is(0)).body("preparation_count", is(0));
    }
}
