package demo.adtech;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectWriter;

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
    private final ObjectWriter bidPayloadWriter;

    public BidReceiverController(
            BidPublisher bidPublisher,
            BenchmarkSettings benchmarkSettings,
            ObjectWriter bidPayloadWriter
    ) {
        this.bidPublisher = bidPublisher;
        this.benchmarkSettings = benchmarkSettings;
        this.bidPayloadWriter = bidPayloadWriter;
    }

    @PostMapping("/bid-request")
    public ResponseEntity<Map<String, String>> receiveBid(@RequestBody byte[] body, jakarta.servlet.http.HttpServletRequest httpRequest) {
        OwnedPipeline.Preparation preparation = (OwnedPipeline.Preparation) httpRequest.getAttribute(PreparationFilter.LEASE);
        try { return handleRequest(ReceiverContract.parse(body), preparation); }
        catch (ReceiverContract.Invalid invalid) { return ResponseEntity.status(invalid.status).body(BAD_REQUEST); }
        finally { if (preparation != null) preparation.close(); }
    }

    ResponseEntity<Map<String,String>> handleRequest(BidRequest request, OwnedPipeline.Preparation preparation) {
        if (request.device.lmt == 1) {
            return ResponseEntity.noContent().build();
        }

        if (request.device.ip != null && request.device.ip.startsWith("10.10.")) {
            return ResponseEntity.noContent().build();
        }

        if (benchmarkSettings.isHttpOnlyMode()) {
            return ResponseEntity.ok(ACCEPTED);
        }

        try {
            java.util.concurrent.CompletableFuture<Void> outcome = bidPublisher.publishPrepared(request.id, () -> bidPayloadWriter.writeValueAsBytes(request),
                    benchmarkSettings.isConfirmDeliveryMode(), preparation);
            if (preparation != null) preparation.close();
            outcome.join();
        } catch (CompletionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof OwnedPipeline.Failure failure) return ResponseEntity.status(failure.status)
                    .body(failure.status == 500 ? SERIALIZATION_ERROR : Map.of("status", failure.getMessage()));
            if (cause instanceof PublisherBackpressureException) return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(KAFKA_BUFFER_FULL);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(KAFKA_UNAVAILABLE);
        }
        return ResponseEntity.ok(ACCEPTED);
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "healthy");
    }
}
