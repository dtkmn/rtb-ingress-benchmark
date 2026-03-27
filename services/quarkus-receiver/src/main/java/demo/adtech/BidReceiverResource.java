package demo.adtech;

import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.Map;

@Path( "/bid-request")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class BidReceiverResource {

    private static final Logger LOG = Logger.getLogger(BidReceiverResource.class);

    @Inject
    BidPublisher bidPublisher;

    @Inject
    BenchmarkSettings benchmarkSettings;

    @POST
    // Returning 'Uni<Response>' means this method is non-blocking (reactive).
    // It returns a "promise" of a response, freeing up the I/O thread immediately.
    public Uni<Response> receiveBid(BidRequest request) {

        // --- STAGE 1: FAST VALIDATION (The "Bouncer") ---
        // Fail instantly if basic required data is missing.
        if (request.id == null || (request.site == null && request.app == null) || request.device == null) {
            // 400 Bad Request - Don't waste any more CPU cycles on this.
            return Uni.createFrom().item(Response.status(Response.Status.BAD_REQUEST).build());
        }

        // --- STAGE 2: SIMPLE BUSINESS FILTERING ---
        // Example: We don't bid on users who have requested "Limit Ad Tracking" (lmt=1)
        if (request.device.lmt == 1) {
            // 204 No Content tells the exchange "We pass, not interested."
            return Uni.createFrom().item(Response.noContent().build());
        }

        // Example: Throttle specific IP ranges (simplified for demo)
        if (request.device.ip != null && request.device.ip.startsWith("10.10.")) {
            return Uni.createFrom().item(Response.noContent().build());
        }

        if (benchmarkSettings.isHttpOnlyMode()) {
            return Uni.createFrom().item(Response.ok(Map.of("status", "accepted")).build());
        }

        // --- STAGE 3: PUSH TO KAFKA & ACKNOWLEDGE ---
        // If it passed the filters, it's a "good" request. Push it to the Decision Engine.
        var delivery = bidPublisher.publish(request);

        if (!benchmarkSettings.isConfirmDeliveryMode()) {
            return Uni.createFrom().item(Response.ok(Map.of("status", "accepted")).build());
        }

        return Uni.createFrom().completionStage(() -> delivery)
                .replaceWith(Response.ok(Map.of("status", "accepted")).build())
                .onFailure().invoke(throwable -> LOG.error("Kafka delivery failed", throwable))
                .onFailure().recoverWithItem(
                        Response.status(Response.Status.SERVICE_UNAVAILABLE)
                                .entity(Map.of("status", "kafka unavailable"))
                                .build()
                );
    }

}
