package demo.adtech;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.util.Map;

class BidReceiverControllerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

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

        if (!publisher.lastConfirm) {
            throw new AssertionError("Expected confirm mode to wait for Kafka delivery");
        }
    }

    @Test
    void returnsBadRequestForIncompletePayload() throws Exception {
        WebTestClient webTestClient = buildWebTestClient(
                BenchmarkSettings.forTests("confirm", "localhost:9092", "bids", "1"),
                new RecordingPublisher(Mono.empty())
        );

        webTestClient.post()
                .uri("/bid-request")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"id\":\"req-1\",\"site\":{\"id\":\"site-1\"}}")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.status").isEqualTo("bad request");
    }

    @Test
    void returnsBadRequestForMalformedJson() throws Exception {
        WebTestClient webTestClient = buildWebTestClient(
                BenchmarkSettings.forTests("confirm", "localhost:9092", "bids", "1"),
                new RecordingPublisher(Mono.empty())
        );

        webTestClient.post()
                .uri("/bid-request")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.status").isEqualTo("bad request");
    }

    @Test
    void filtersLimitAdTrackingRequests() throws Exception {
        WebTestClient webTestClient = buildWebTestClient(
                BenchmarkSettings.forTests("confirm", "localhost:9092", "bids", "1"),
                new RecordingPublisher(Mono.empty())
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
        return WebTestClient.bindToController(new BidReceiverController(publisher, settings, objectMapper))
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
        private boolean lastConfirm;

        private RecordingPublisher(Mono<Void> result) {
            this.result = result;
        }

        @Override
        public Mono<Void> publish(String key, byte[] payload, boolean confirm) {
            this.lastConfirm = confirm;
            return result;
        }
    }
}
