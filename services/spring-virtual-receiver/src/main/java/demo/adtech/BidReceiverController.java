package demo.adtech;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.concurrent.CompletionException;

@RestController
public class BidReceiverController {

    private static final Map<String, String> ACCEPTED = Map.of("status", "accepted");
    private static final Map<String, String> BAD_REQUEST = Map.of("status", "bad request");
    private static final Map<String, String> KAFKA_UNAVAILABLE = Map.of("status", "kafka unavailable");
    private static final Map<String, String> KAFKA_BUFFER_FULL = Map.of("status", "kafka buffer full");
    private static final Map<String, String> SERIALIZATION_ERROR = Map.of("status", "serialization error");

    private final BidPublisher bidPublisher;
    private final BenchmarkSettings benchmarkSettings;
    private final ObjectMapper objectMapper;

    public BidReceiverController(
            BidPublisher bidPublisher,
            BenchmarkSettings benchmarkSettings,
            ObjectMapper objectMapper
    ) {
        this.bidPublisher = bidPublisher;
        this.benchmarkSettings = benchmarkSettings;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/bid-request")
    public ResponseEntity<Map<String, String>> receiveBid(@RequestBody BidRequest request) {
        if (request.id == null || request.id.isBlank() || request.device == null
                || (request.site == null && request.app == null)) {
            return ResponseEntity.badRequest().body(BAD_REQUEST);
        }

        if (request.device.lmt == 1) {
            return ResponseEntity.noContent().build();
        }

        if (request.device.ip != null && request.device.ip.startsWith("10.10.")) {
            return ResponseEntity.noContent().build();
        }

        if (benchmarkSettings.isHttpOnlyMode()) {
            return ResponseEntity.ok(ACCEPTED);
        }

        byte[] payload;
        try {
            payload = objectMapper.writeValueAsBytes(request);
        } catch (JsonProcessingException exception) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(SERIALIZATION_ERROR);
        }

        try {
            bidPublisher.publish(request.id, payload, benchmarkSettings.isConfirmDeliveryMode()).join();
        } catch (CompletionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof PublisherBackpressureException) {
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(KAFKA_BUFFER_FULL);
            }
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(KAFKA_UNAVAILABLE);
        }

        return ResponseEntity.ok(ACCEPTED);
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "healthy");
    }
}
