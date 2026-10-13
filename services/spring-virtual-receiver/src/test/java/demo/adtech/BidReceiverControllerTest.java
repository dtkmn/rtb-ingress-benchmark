package demo.adtech;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class BidReceiverControllerTest {

    private final JsonMapper objectMapper = JsonMapper.builder().build();

    @Test
    void acceptsInHttpOnlyMode() throws Exception {
        RecordingPublisher publisher = new RecordingPublisher(CompletableFuture.completedFuture(null));
        MockMvc mockMvc = buildMockMvc(
                BenchmarkSettings.forTests("http-only", "localhost:9092", "bids", "1"),
                publisher
        );

        mockMvc.perform(post("/bid-request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validPayload()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("accepted"));

        assertEquals(0, publisher.publishCount);
    }

    @Test
    void confirmsKafkaDeliveryWhenConfigured() throws Exception {
        RecordingPublisher publisher = new RecordingPublisher(CompletableFuture.completedFuture(null));
        MockMvc mockMvc = buildMockMvc(
                BenchmarkSettings.forTests("confirm", "localhost:9092", "bids", "all"),
                publisher
        );

        mockMvc.perform(post("/bid-request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validPayload()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("accepted"));

        assertEquals(1, publisher.publishCount);
        assertTrue(publisher.lastConfirm);
        assertEquals("req-1", publisher.lastKey);
        assertArrayEquals(("{\"id\":\"req-1\",\"site\":{\"id\":\"site-1\",\"domain\":\"example.com\"},"
                + "\"device\":{\"ip\":\"1.2.3.4\",\"ua\":\"test\",\"lmt\":0}}")
                .getBytes(StandardCharsets.UTF_8), publisher.lastPayload);
    }

    @Test
    void preservesSitePayloadBytesAndIgnoresUnknownFields() throws Exception {
        RecordingPublisher publisher = new RecordingPublisher(CompletableFuture.completedFuture(null));
        MockMvc mockMvc = buildMockMvc(
                BenchmarkSettings.forTests("confirm", "localhost:9092", "bids", "all"),
                publisher
        );

        mockMvc.perform(post("/bid-request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"unknown":true,"user":{"unknown":true,"id":"user-1"},
                                 "device":{"lmt":0,"ua":"café 😀 https://example.com","os":"Android","ip":"1.2.3.4","unknown":true},
                                 "app":null,"site":{"domain":"example.com","id":"site-1","unknown":true},
                                 "id":"req-site"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("accepted"));

        assertEquals(1, publisher.publishCount);
        assertTrue(publisher.lastConfirm);
        assertEquals("req-site", publisher.lastKey);
        assertArrayEquals(("{\"id\":\"req-site\",\"site\":{\"id\":\"site-1\",\"domain\":\"example.com\"},"
                + "\"device\":{\"ip\":\"1.2.3.4\",\"os\":\"Android\",\"ua\":\"café \\uD83D\\uDE00 https://example.com\",\"lmt\":0},"
                + "\"user\":{\"id\":\"user-1\"}}")
                .getBytes(StandardCharsets.UTF_8), publisher.lastPayload);
    }

    @Test
    void enqueuesAppPayloadWithNullsOmittedAndDefaultLimitAdTracking() throws Exception {
        RecordingPublisher publisher = new RecordingPublisher(CompletableFuture.completedFuture(null));
        MockMvc mockMvc = buildMockMvc(
                BenchmarkSettings.forTests("enqueue", "localhost:9092", "bids", "1"),
                publisher
        );

        mockMvc.perform(post("/bid-request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"user":null,"device":{"ua":null,"os":null,"ip":"1.2.3.4","unknown":true},
                                 "app":{"bundle":"demo.app","id":"app-1","unknown":true},
                                 "site":null,"id":"req-app","unknown":true}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("accepted"));

        assertEquals(1, publisher.publishCount);
        assertFalse(publisher.lastConfirm);
        assertEquals("req-app", publisher.lastKey);
        assertArrayEquals(("{\"id\":\"req-app\",\"app\":{\"id\":\"app-1\",\"bundle\":\"demo.app\"},"
                + "\"device\":{\"ip\":\"1.2.3.4\",\"lmt\":0}}")
                .getBytes(StandardCharsets.UTF_8), publisher.lastPayload);
    }

    @Test
    void returnsBadRequestForIncompletePayload() throws Exception {
        RecordingPublisher publisher = new RecordingPublisher(CompletableFuture.completedFuture(null));
        MockMvc mockMvc = buildMockMvc(
                BenchmarkSettings.forTests("confirm", "localhost:9092", "bids", "1"),
                publisher
        );

        mockMvc.perform(post("/bid-request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"req-1\",\"site\":{\"id\":\"site-1\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("bad request"));

        assertEquals(0, publisher.publishCount);
    }

    @Test
    void returnsBadRequestForMalformedJson() throws Exception {
        RecordingPublisher publisher = new RecordingPublisher(CompletableFuture.completedFuture(null));
        MockMvc mockMvc = buildMockMvc(
                BenchmarkSettings.forTests("confirm", "localhost:9092", "bids", "1"),
                publisher
        );

        mockMvc.perform(post("/bid-request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("bad request"));

        assertEquals(0, publisher.publishCount);
    }

    @Test
    void filtersLimitAdTrackingRequests() throws Exception {
        RecordingPublisher publisher = new RecordingPublisher(CompletableFuture.completedFuture(null));
        MockMvc mockMvc = buildMockMvc(
                BenchmarkSettings.forTests("confirm", "localhost:9092", "bids", "1"),
                publisher
        );

        mockMvc.perform(post("/bid-request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "id", "req-1",
                                "site", Map.of("id", "site-1", "domain", "example.com"),
                                "device", Map.of("ip", "1.2.3.4", "lmt", 1)
                        ))))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        assertEquals(0, publisher.publishCount);
    }

    @Test
    void filtersInternalIpRequests() throws Exception {
        RecordingPublisher publisher = new RecordingPublisher(CompletableFuture.completedFuture(null));
        MockMvc mockMvc = buildMockMvc(
                BenchmarkSettings.forTests("confirm", "localhost:9092", "bids", "1"),
                publisher
        );

        mockMvc.perform(post("/bid-request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "id", "req-1",
                                "site", Map.of("id", "site-1", "domain", "example.com"),
                                "device", Map.of("ip", "10.10.1.2", "lmt", 0)
                        ))))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        assertEquals(0, publisher.publishCount);
    }

    @Test
    void mapsSerializationFailureToInternalServerError() {
        BidRequest request = new BidRequest() {
            public String getInvalid() {
                throw new IllegalStateException("Cannot serialize bid request");
            }
        };
        request.id = "req-1";
        request.site = new BidRequest.Site();
        request.site.id = "site-1";
        request.device = new BidRequest.Device();
        request.device.ip = "1.2.3.4";
        RecordingPublisher publisher = new RecordingPublisher(CompletableFuture.completedFuture(null));
        BidReceiverController controller = new BidReceiverController(
                publisher,
                BenchmarkSettings.forTests("confirm", "localhost:9092", "bids", "1"),
                new Application().bidPayloadWriter()
        );

        var response = controller.receiveBid(request);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertEquals(Map.of("status", "serialization error"), response.getBody());
        assertEquals(0, publisher.publishCount);
    }

    @Test
    void mapsKafkaBackpressureToServiceUnavailable() throws Exception {
        CompletableFuture<Void> failed = new CompletableFuture<>();
        failed.completeExceptionally(new PublisherBackpressureException(new IllegalStateException("busy")));
        MockMvc mockMvc = buildMockMvc(
                BenchmarkSettings.forTests("confirm", "localhost:9092", "bids", "1"),
                new RecordingPublisher(failed)
        );

        mockMvc.perform(post("/bid-request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validPayload()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("kafka buffer full"));
    }

    @Test
    void mapsKafkaFailureToServiceUnavailable() throws Exception {
        MockMvc mockMvc = buildMockMvc(
                BenchmarkSettings.forTests("confirm", "localhost:9092", "bids", "1"),
                new RecordingPublisher(CompletableFuture.failedFuture(new IllegalStateException("offline")))
        );

        mockMvc.perform(post("/bid-request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validPayload()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("kafka unavailable"));
    }

    @Test
    void exposesHealthEndpoint() throws Exception {
        MockMvc mockMvc = buildMockMvc(
                BenchmarkSettings.forTests("http-only", "localhost:9092", "bids", "1"),
                new RecordingPublisher(CompletableFuture.completedFuture(null))
        );

        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("healthy"));
    }

    private MockMvc buildMockMvc(BenchmarkSettings settings, BidPublisher publisher) {
        return MockMvcBuilders.standaloneSetup(
                        new BidReceiverController(publisher, settings, new Application().bidPayloadWriter())
                )
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private String validPayload() throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "id", "req-1",
                "site", Map.of("id", "site-1", "domain", "example.com"),
                "device", Map.of("ip", "1.2.3.4", "ua", "test", "lmt", 0)
        ));
    }

    private static final class RecordingPublisher implements BidPublisher {
        private final CompletableFuture<Void> result;
        private int publishCount;
        private boolean lastConfirm;
        private String lastKey;
        private byte[] lastPayload;

        private RecordingPublisher(CompletableFuture<Void> result) {
            this.result = result;
        }

        @Override
        public CompletableFuture<Void> publish(String key, byte[] payload, boolean confirm) {
            this.publishCount++;
            this.lastConfirm = confirm;
            this.lastKey = key;
            this.lastPayload = payload.clone();
            return result;
        }
    }
}
