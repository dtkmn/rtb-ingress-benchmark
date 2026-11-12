package demo.adtech;

import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Map;

@Path( "/bid-request")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class BidReceiverResource {

    private static final Map<String, String> ACCEPTED = Map.of("status", "accepted");
    private static final Map<String, String> KAFKA_UNAVAILABLE = Map.of("status", "kafka unavailable");

    @Inject
    BidPublisher bidPublisher;

    @Inject
    BenchmarkSettings benchmarkSettings;

    @POST
    // Returning 'Uni<Response>' means this method is non-blocking (reactive).
    // It returns a "promise" of a response, freeing up the I/O thread immediately.
    public Uni<Response> receiveBid(@jakarta.ws.rs.core.Context io.vertx.ext.web.RoutingContext context) {
        OwnedPipeline.Preparation preparation = context.get(PreparationFilter.LEASE);
        try {
            // Parse the exact bounded bytes before any filtering or Kafka serialization.
            io.vertx.core.buffer.Buffer body = context.body().buffer();
            BidRequest request = ReceiverContract.parse(body == null ? new byte[0] : body.getBytes());
            return handle(request, preparation);
        } catch (ReceiverContract.Invalid invalid) {
            return Uni.createFrom().item(Response.status(invalid.status).build());
        } finally { if (preparation != null) preparation.close(); }
    }
    private Uni<Response> handle(BidRequest request, OwnedPipeline.Preparation preparation) {

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
            return Uni.createFrom().item(Response.ok(ACCEPTED).build());
        }

        // Both modes await owned admission; only confirm awaits the client outcome.
        return Uni.createFrom().completionStage(bidPublisher.publish(request, preparation))
                .replaceWith(Response.ok(ACCEPTED).build())
                .onFailure().recoverWithItem(failure -> Response.status(
                        failure instanceof OwnedPipeline.Failure pipelineFailure ? pipelineFailure.status : 503)
                        .entity(KAFKA_UNAVAILABLE).build());
    }
}
