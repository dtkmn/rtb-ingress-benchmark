package demo.adtech;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.BodyInserters;
import reactor.core.publisher.Flux;
import java.util.ArrayList;
import static org.junit.jupiter.api.Assertions.*;

class PreparationFilterTest {
    @Test void gatesHttpOnlyBeforeReadingAndReleasesAllLeases() {
        BenchmarkSettings settings = BenchmarkSettings.forTests("http-only", "localhost:9092", "bids", "1");
        KafkaBidPublisher publisher = new KafkaBidPublisher(settings);
        WebTestClient client = WebTestClient.bindToController(new BidReceiverController(publisher, settings, new Application().bidPayloadWriter()))
                .controllerAdvice(new ApiExceptionHandler()).webFilter(new PreparationFilter(publisher)).build();
        ArrayList<OwnedPipeline.Preparation> occupied = new ArrayList<>();
        try {
            for (int index = 0; index < 128; index++) occupied.add(publisher.pipeline().prepare());
            client.post().uri("/bid-request").contentType(MediaType.TEXT_PLAIN).bodyValue("{").exchange().expectStatus().isEqualTo(415);
            assertEquals(0L, publisher.pipeline().state().get("preparation_rejected"));
            client.post().uri("/bid-request").contentType(MediaType.APPLICATION_JSON).bodyValue("{").exchange().expectStatus().isEqualTo(503);
            assertEquals(1L, publisher.pipeline().state().get("preparation_rejected"));
            assertEquals(1L, httpCount(publisher, "503"));
            occupied.forEach(OwnedPipeline.Preparation::close);
            client.post().uri("/bid-request").contentType(MediaType.APPLICATION_JSON).bodyValue("{").exchange().expectStatus().isBadRequest();
            client.post().uri("/bid-request").contentType(MediaType.APPLICATION_JSON).exchange().expectStatus().isBadRequest();
            assertEquals(0, publisher.pipeline().state().get("preparation_count"));
            assertEquals(2L, httpCount(publisher, "400"));
        } finally { occupied.forEach(OwnedPipeline.Preparation::close); publisher.close(); }
    }
    @Test void unknownLengthStreamingBodyChecksActualBytesAtExactBoundary() {
        BenchmarkSettings settings = BenchmarkSettings.forTests("http-only", "localhost:9092", "bids", "1");
        KafkaBidPublisher publisher = new KafkaBidPublisher(settings);
        WebTestClient client = WebTestClient.bindToController(new BidReceiverController(publisher, settings, new Application().bidPayloadWriter()))
                .controllerAdvice(new ApiExceptionHandler()).webFilter(new PreparationFilter(publisher)).build();
        try {
            String json = "{\"id\":\"k\",\"site\":{},\"device\":{}}";
            for (int length : new int[]{65536,65537}) {
                byte[] body = (json + " ".repeat(length-json.length())).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                client.post().uri("/bid-request").contentType(MediaType.APPLICATION_JSON)
                        .body(BodyInserters.fromDataBuffers(Flux.just(new DefaultDataBufferFactory().wrap(body))))
                        .exchange().expectStatus().isEqualTo(length == 65536 ? 200 : 413);
            }
            assertEquals(0, publisher.pipeline().state().get("preparation_count"));
            assertEquals(1L, httpCount(publisher, "200"));
            assertEquals(1L, httpCount(publisher, "413"));
        } finally { publisher.close(); }
    }
    private static long httpCount(KafkaBidPublisher publisher, String status) {
        java.util.Map<?,?> counts = (java.util.Map<?,?>) publisher.pipeline().state().get("http_status_counts");
        return ((Number) counts.get(status)).longValue();
    }
}
