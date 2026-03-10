package demo.adtech;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.eclipse.microprofile.reactive.messaging.OnOverflow;
import org.jboss.logging.Logger;

import java.time.Instant;

/**
 * Dead Letter Queue service for handling failed bid processing.
 * Failed messages are sent to a separate Kafka topic for later analysis and reprocessing.
 */
@ApplicationScoped
public class DeadLetterQueueService {

    private static final Logger LOG = Logger.getLogger(DeadLetterQueueService.class);

    @ConfigProperty(name = "sinker.dlq.enabled", defaultValue = "false")
    boolean dlqEnabled;

    @Inject
    @Channel("dlq-out")
    @OnOverflow(value = OnOverflow.Strategy.BUFFER, bufferSize = 1000)
    Emitter<FailedBidRecord> dlqEmitter;

    public boolean isEnabled() {
        return dlqEnabled;
    }

    /**
     * Sends a failed bid request to the Dead Letter Queue.
     *
     * @param request   The original bid request that failed processing
     * @param error     The exception that caused the failure
     * @param operation The operation that was being performed when the failure occurred
     */
    public void sendToDeadLetterQueue(BidRequest request, Throwable error, String operation) {
        if (!dlqEnabled) {
            LOG.debugf("DLQ disabled, dropping failed bid %s", request.id);
            return;
        }

        try {
            FailedBidRecord failedRecord = new FailedBidRecord(
                    request,
                    error.getClass().getName(),
                    error.getMessage(),
                    operation,
                    Instant.now()
            );

            dlqEmitter.send(failedRecord);
            LOG.warnf("Sent failed bid %s to DLQ. Error: %s", request.id, error.getMessage());

        } catch (Exception dlqError) {
            // Last resort logging if even DLQ fails
            LOG.errorf(dlqError, "CRITICAL: Failed to send bid %s to DLQ. Original error: %s",
                    request.id, error.getMessage());
        }
    }

    /**
     * Record representing a failed bid that goes to the DLQ.
     */
    public record FailedBidRecord(
            BidRequest originalRequest,
            String errorType,
            String errorMessage,
            String failedOperation,
            Instant failedAt
    ) {}
}
