package demo.adtech;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BidReceiverControllerTest {

    private final JsonMapper objectMapper = JsonMapper.builder().build();

    @Test
    void acceptsInHttpOnlyMode() throws Exception {
        RecordingPublisher publisher = new RecordingPublisher(Mono.empty());
        WebTestClient webTestClient = buildWebTestClient(
                BenchmarkSettings.forTests("http-only", "localhost:9092", "bids", "1"),
                publisher
        );

        webTestClient.post()
                .uri("/bid-request")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(validPayload())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("accepted");

        assertEquals(0, publisher.publishCount);
    }

    @Test
    void confirmsKafkaDeliveryWhenConfigured() throws Exception {
        RecordingPublisher publisher = new RecordingPublisher(Mono.empty());
        WebTestClient webTestClient = buildWebTestClient(
                BenchmarkSettings.forTests("confirm", "localhost:9092", "bids", "all"),
                publisher
        );

        webTestClient.post()
                .uri("/bid-request")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(validPayload())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("accepted");

        assertEquals(1, publisher.publishCount);
        assertTrue(publisher.lastConfirm);
        assertEquals("req-1", publisher.lastKey);
        assertArrayEquals(("{\"id\":\"req-1\",\"site\":{\"id\":\"site-1\",\"domain\":\"example.com\"},"
                + "\"device\":{\"ip\":\"1.2.3.4\",\"ua\":\"test\",\"lmt\":0}}")
                .getBytes(StandardCharsets.UTF_8), publisher.lastPayload);
    }

    @Test
    void preservesSitePayloadBytesAndIgnoresUnknownFields() throws Exception {
        RecordingPublisher publisher = new RecordingPublisher(Mono.empty());
        WebTestClient webTestClient = buildWebTestClient(
                BenchmarkSettings.forTests("confirm", "localhost:9092", "bids", "all"), publisher
        );

        webTestClient.post()
                .uri("/bid-request")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"unknown":true,"user":{"unknown":true,"id":"user-1"},
                         "device":{"lmt":0,"ua":"café 😀 https://example.com","os":"Android","ip":"1.2.3.4","unknown":true},
                         "app":null,"site":{"domain":"example.com","id":"site-1","unknown":true},
                         "id":"req-site"}
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("accepted");

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
        RecordingPublisher publisher = new RecordingPublisher(Mono.empty());
        WebTestClient webTestClient = buildWebTestClient(
                BenchmarkSettings.forTests("enqueue", "localhost:9092", "bids", "1"), publisher
        );

        webTestClient.post()
                .uri("/bid-request")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"user":null,"device":{"ua":null,"os":null,"ip":"1.2.3.4","unknown":true},
                         "app":{"bundle":"demo.app","id":"app-1","unknown":true},
                         "site":null,"id":"req-app","unknown":true}
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("accepted");

        assertEquals(1, publisher.publishCount);
        assertFalse(publisher.lastConfirm);
        assertEquals("req-app", publisher.lastKey);
        assertArrayEquals(("{\"id\":\"req-app\",\"app\":{\"id\":\"app-1\",\"bundle\":\"demo.app\"},"
                + "\"device\":{\"ip\":\"1.2.3.4\",\"lmt\":0}}")
                .getBytes(StandardCharsets.UTF_8), publisher.lastPayload);
    }

    @Test
    void returnsBadRequestForIncompletePayload() throws Exception {
        RecordingPublisher publisher = new RecordingPublisher(Mono.empty());
        WebTestClient webTestClient = buildWebTestClient(
                BenchmarkSettings.forTests("confirm", "localhost:9092", "bids", "1"),
                publisher
        );

        webTestClient.post()
                .uri("/bid-request")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"id\":\"req-1\",\"site\":{\"id\":\"site-1\"}}")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.status").isEqualTo("bad request");

        assertEquals(0, publisher.publishCount);
    }

    @Test
    void returnsBadRequestForMalformedJson() throws Exception {
        RecordingPublisher publisher = new RecordingPublisher(Mono.empty());
        WebTestClient webTestClient = buildWebTestClient(
                BenchmarkSettings.forTests("confirm", "localhost:9092", "bids", "1"),
                publisher
        );

        webTestClient.post()
                .uri("/bid-request")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.status").isEqualTo("bad request");

        assertEquals(0, publisher.publishCount);
    }

    @Test
    void filtersLimitAdTrackingRequests() throws Exception {
        RecordingPublisher publisher = new RecordingPublisher(Mono.empty());
        WebTestClient webTestClient = buildWebTestClient(
                BenchmarkSettings.forTests("confirm", "localhost:9092", "bids", "1"),
                publisher
        );

        webTestClient.post()
                .uri("/bid-request")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(objectMapper.writeValueAsString(Map.of(
                        "id", "req-1",
                        "site", Map.of("id", "site-1", "domain", "example.com"),
                        "device", Map.of("ip", "1.2.3.4", "lmt", 1)
                )))
                .exchange()
                .expectStatus().isNoContent()
                .expectBody().isEmpty();

        assertEquals(0, publisher.publishCount);
    }

    @Test
    void filtersInternalIpRequests() throws Exception {
        RecordingPublisher publisher = new RecordingPublisher(Mono.empty());
        WebTestClient webTestClient = buildWebTestClient(
                BenchmarkSettings.forTests("confirm", "localhost:9092", "bids", "1"), publisher
        );

        webTestClient.post()
                .uri("/bid-request")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(objectMapper.writeValueAsString(Map.of(
                        "id", "req-1", "site", Map.of("id", "site-1", "domain", "example.com"),
                        "device", Map.of("ip", "10.10.1.2", "lmt", 0)
                )))
                .exchange()
                .expectStatus().isNoContent()
                .expectBody().isEmpty();

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
        RecordingPublisher publisher = new RecordingPublisher(Mono.empty());
        BidReceiverController controller = new BidReceiverController(
                publisher, BenchmarkSettings.forTests("confirm", "localhost:9092", "bids", "1"),
                new Application().bidPayloadWriter()
        );

        var response = controller.receiveBid(Mono.just(request)).block();

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertEquals(Map.of("status", "serialization error"), response.getBody());
        assertEquals(0, publisher.publishCount);
    }

    @Test
    void mapsKafkaBackpressureToServiceUnavailable() throws Exception {
        WebTestClient webTestClient = buildWebTestClient(
                BenchmarkSettings.forTests("confirm", "localhost:9092", "bids", "1"),
                new RecordingPublisher(Mono.error(new PublisherBackpressureException(new IllegalStateException("busy"))))
        );

        webTestClient.post()
                .uri("/bid-request")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(validPayload())
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectBody()
                .jsonPath("$.status").isEqualTo("kafka buffer full");
    }

    @Test
    void mapsKafkaFailureToServiceUnavailable() throws Exception {
        WebTestClient webTestClient = buildWebTestClient(
                BenchmarkSettings.forTests("confirm", "localhost:9092", "bids", "1"),
                new RecordingPublisher(Mono.error(new IllegalStateException("offline")))
        );

        webTestClient.post().uri("/bid-request")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(validPayload())
                .exchange().expectStatus().isEqualTo(503)
                .expectBody().jsonPath("$.status").isEqualTo("kafka unavailable");
    }

    @Test
    void exposesHealthEndpoint() throws Exception {
        WebTestClient webTestClient = buildWebTestClient(
                BenchmarkSettings.forTests("http-only", "localhost:9092", "bids", "1"),
                new RecordingPublisher(Mono.empty())
        );

        webTestClient.get()
                .uri("/health")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("healthy");
    }

    private WebTestClient buildWebTestClient(BenchmarkSettings settings, BidPublisher publisher) {
        return WebTestClient.bindToController(new BidReceiverController(publisher, settings, new Application().bidPayloadWriter()))
                .controllerAdvice(new ApiExceptionHandler())
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
        private final Mono<Void> result;
        private int publishCount;
        private boolean lastConfirm;
        private String lastKey;
        private byte[] lastPayload;

        private RecordingPublisher(Mono<Void> result) {
            this.result = result;
        }

        @Override
        public Mono<Void> publish(String key, byte[] payload, boolean confirm) {
            this.publishCount++;
            this.lastConfirm = confirm;
            this.lastKey = key;
            this.lastPayload = payload.clone();
            return result;
        }
    }
}
