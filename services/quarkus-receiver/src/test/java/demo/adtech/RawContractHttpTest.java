package demo.adtech;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

@io.quarkus.test.junit.QuarkusTest
@io.quarkus.test.junit.TestProfile(PreparationHttpTest.HttpOnlyProfile.class)
class RawContractHttpTest {
    
    @jakarta.inject.Inject BidPublisher publisher;
    @Test void actualRawNonfaultCorpus() throws Exception {
        try (HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
            int checked = 0;
            for (var fixture : ContractFixtures.cases()) {
                HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:"+io.restassured.RestAssured.port+"/bid-request")).timeout(Duration.ofSeconds(5));
                for (var header : fixture.get("request").get("headers")) request.header(header.get("name").asText(),header.get("value").asText());
                HttpResponse<byte[]> response = client.send(request.POST(HttpRequest.BodyPublishers.ofByteArray(ContractFixtures.body(fixture))).build(),HttpResponse.BodyHandlers.ofByteArray());
                assertEquals(ContractFixtures.status(fixture),response.statusCode(),fixture.get("id").asText());
                if (response.statusCode()==200) assertEquals(ContractFixtures.JSON.readTree("{\"status\":\"accepted\"}"),ContractFixtures.JSON.readTree(response.body()));
                if (response.statusCode()==204) assertEquals(0,response.body().length,fixture.get("id").asText());
                checked++;
            }
            assertEquals(52,checked);
            System.out.println("F3 actual raw HTTP nonfault corpus:52/52 mode=http-only");
        }
        assertEquals(0L, publisher.pipeline().state().get("admission_candidates"));
        assertEquals(0,publisher.pipeline().state().get("preparation_count"));
    }
    @Test void actualWhitespaceMediaAndIdentityEncoding() throws Exception {
        try (HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
            byte[] body="{\"id\":\" \\u0000\\t\\n key \" ,\"site\":{},\"device\":{}}".getBytes(StandardCharsets.UTF_8);
            HttpRequest request=HttpRequest.newBuilder(URI.create("http://localhost:"+io.restassured.RestAssured.port+"/bid-request"))
                    .header("Content-Type","application/json ; charset = \"utf-8\"")
                    .header("Content-Encoding","IDENTITY").POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
            assertEquals(200,client.send(request,HttpResponse.BodyHandlers.discarding()).statusCode());
        }
        assertEquals(0,publisher.pipeline().state().get("preparation_count"));
    }
    @Test void actualHeaderPrecedenceAndUnknownLengthSizeBoundary() throws Exception {
        try (HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
            byte[] tooLarge = new byte[65537]; java.util.Arrays.fill(tooLarge,(byte)'x');
            HttpRequest badMedia=HttpRequest.newBuilder(URI.create("http://localhost:"+io.restassured.RestAssured.port+"/bid-request"))
                    .header("Content-Type","text/plain").POST(HttpRequest.BodyPublishers.ofByteArray(tooLarge)).build();
            assertEquals(415,client.send(badMedia,HttpResponse.BodyHandlers.discarding()).statusCode());
            String json="{\"id\":\"k\",\"site\":{},\"device\":{}}";
            for (int length : new int[]{65536,65537}) {
                byte[] body=(json+" ".repeat(length-json.length())).getBytes(StandardCharsets.UTF_8);
                HttpRequest request=HttpRequest.newBuilder(URI.create("http://localhost:"+io.restassured.RestAssured.port+"/bid-request"))
                        .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofInputStream(() -> new java.io.ByteArrayInputStream(body))).build();
                assertEquals(length==65536?200:413,client.send(request,HttpResponse.BodyHandlers.discarding()).statusCode());
            }
        }
        assertEquals(0,publisher.pipeline().state().get("preparation_count"));
    }
    @Test void actualRuntimeEvidenceAfterValidAndInvalidRequests() throws Exception {
        try (HttpClient client=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
            for (String body:new String[]{"{\"id\":\"runtime-check\",\"site\":{},\"device\":{}}","{"}) {
                HttpRequest request=HttpRequest.newBuilder(URI.create("http://localhost:"+io.restassured.RestAssured.port+"/bid-request"))
                        .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
                assertTrue(java.util.Set.of(200,400).contains(client.send(request,HttpResponse.BodyHandlers.discarding()).statusCode()));
            }
            HttpResponse<byte[]> response=client.send(HttpRequest.newBuilder(URI.create("http://localhost:"+io.restassured.RestAssured.port+"/benchmark/runtime")).GET().build(),HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200,response.statusCode(),new String(response.body(),StandardCharsets.UTF_8));
            var state=ContractFixtures.JSON.readTree(response.body());assertEquals(1,state.get("schema_version").asInt());
            assertEquals("controlled",state.get("telemetry").get("profile").asText());
            for (String key:new String[]{"access_log","tracing","trace_export","body_size_metrics","request_latency_histogram"}) assertFalse(state.get("telemetry").get(key).asBoolean(),key);
            assertEquals(0,state.get("producer").get("instances").asInt());assertEquals("not_applicable",state.get("producer").get("controls").get("linger_ms").get("status").asText());
            assertEquals(Long.parseLong(java.lang.management.ManagementFactory.getPlatformMXBean(
                    com.sun.management.HotSpotDiagnosticMXBean.class).getVMOption("MaxHeapSize").getValue()),
                    state.get("runtime").get("heap").get("vm_max_heap_bytes").asLong());
            assertEquals(Runtime.getRuntime().maxMemory(),state.get("runtime").get("heap").get("max_bytes").asLong());assertFalse(state.get("runtime").get("heap").get("gc").isEmpty());
            assertEquals(0,state.get("pipeline").get("admission_candidates").asInt());
            assertTrue(state.get("runtime").get("http_parallelism").get("effective").asInt()>0);
        }
    }
    @Test void conformanceUnauthorizedOrDisabledPathNeverParsesCommands() throws Exception {
        try (HttpClient client=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
            var before=publisher.pipeline().state();
            for (String token:new String[]{"","wrong-conformance-token"}) {
                var builder=HttpRequest.newBuilder(URI.create("http://localhost:"+io.restassured.RestAssured.port+"/benchmark/conformance"));
                if (!token.isEmpty()) builder.header("X-Conformance-Token",token);
                assertEquals(404,client.send(builder.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{")).build(),HttpResponse.BodyHandlers.discarding()).statusCode());
            }
            if (!"1".equals(System.getenv("BENCHMARK_CONFORMANCE"))) {
                var request=HttpRequest.newBuilder(URI.create("http://localhost:"+io.restassured.RestAssured.port+"/benchmark/conformance"))
                        .header("X-Conformance-Token","java-fault-http-token").GET().build();
                assertEquals(404,client.send(request,HttpResponse.BodyHandlers.discarding()).statusCode());
            }
            assertEquals(before,publisher.pipeline().state());
        }
    }
}
