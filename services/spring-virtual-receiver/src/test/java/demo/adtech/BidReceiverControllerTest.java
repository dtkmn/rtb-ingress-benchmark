package demo.adtech;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class BidReceiverControllerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

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

        if (!publisher.lastConfirm) {
            throw new AssertionError("Expected confirm mode to wait for Kafka delivery");
        }
    }

    @Test
    void returnsBadRequestForIncompletePayload() throws Exception {
        MockMvc mockMvc = buildMockMvc(
                BenchmarkSettings.forTests("confirm", "localhost:9092", "bids", "1"),
                new RecordingPublisher(CompletableFuture.completedFuture(null))
        );

        mockMvc.perform(post("/bid-request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"req-1\",\"site\":{\"id\":\"site-1\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("bad request"));
    }

    @Test
    void returnsBadRequestForMalformedJson() throws Exception {
        MockMvc mockMvc = buildMockMvc(
                BenchmarkSettings.forTests("confirm", "localhost:9092", "bids", "1"),
                new RecordingPublisher(CompletableFuture.completedFuture(null))
        );

        mockMvc.perform(post("/bid-request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("bad request"));
    }

    @Test
    void filtersLimitAdTrackingRequests() throws Exception {
        MockMvc mockMvc = buildMockMvc(
                BenchmarkSettings.forTests("confirm", "localhost:9092", "bids", "1"),
                new RecordingPublisher(CompletableFuture.completedFuture(null))
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
                        new BidReceiverController(publisher, settings, objectMapper)
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
        private boolean lastConfirm;

        private RecordingPublisher(CompletableFuture<Void> result) {
            this.result = result;
        }

        @Override
        public CompletableFuture<Void> publish(String key, byte[] payload, boolean confirm) {
            this.lastConfirm = confirm;
            return result;
        }
    }
}
