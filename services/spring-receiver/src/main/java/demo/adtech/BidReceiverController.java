package demo.adtech;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectWriter;

import java.util.Map;

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
    public Mono<ResponseEntity<?>> receiveBid(@RequestBody Mono<byte[]> request, org.springframework.web.server.ServerWebExchange exchange) {
        OwnedPipeline.Preparation preparation = exchange.getAttribute(PreparationFilter.LEASE);
        return request
                .flatMap(value -> {
                    try { return handleRequest(ReceiverContract.parse(value), preparation); }
                    catch (ReceiverContract.Invalid invalid) { return response(ResponseEntity.status(invalid.status).body(BAD_REQUEST)); }
                    finally { if (preparation != null) preparation.close(); }
                })
                .switchIfEmpty(Mono.just(ResponseEntity.badRequest().body(BAD_REQUEST)));
    }

    Mono<ResponseEntity<?>> handleRequest(BidRequest request, OwnedPipeline.Preparation preparation) {
        if (request.device.lmt == 1) {
            return response(ResponseEntity.noContent().build());
        }

        if (request.device.ip != null && request.device.ip.startsWith("10.10.")) {
            return response(ResponseEntity.noContent().build());
        }

        if (benchmarkSettings.isHttpOnlyMode()) {
            return response(ResponseEntity.ok(ACCEPTED));
        }

        return bidPublisher.publishPrepared(request.id, () -> bidPayloadWriter.writeValueAsBytes(request),
                        benchmarkSettings.isConfirmDeliveryMode(), preparation)
                .then(response(ResponseEntity.ok(ACCEPTED)))
                .onErrorResume(failure -> response(mapPublisherFailure(failure)));
    }

    @GetMapping("/health")
    public Mono<Map<String, String>> health() {
        return Mono.just(Map.of("status", "healthy"));
    }

    private static Mono<ResponseEntity<?>> response(ResponseEntity<?> response) {
        return Mono.just(response);
    }

    private static ResponseEntity<?> mapPublisherFailure(Throwable failure) {
        if (failure instanceof OwnedPipeline.Failure pipelineFailure) return ResponseEntity.status(pipelineFailure.status)
                .body(pipelineFailure.status == 500 ? SERIALIZATION_ERROR : Map.of("status", pipelineFailure.getMessage()));
        if (failure instanceof PublisherBackpressureException) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(KAFKA_BUFFER_FULL);
        }
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(KAFKA_UNAVAILABLE);
    }
}
