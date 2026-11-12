package demo.adtech;

import java.io.Closeable;

import reactor.core.publisher.Mono;

public interface BidPublisher extends Closeable {

    Mono<Void> publish(String key, byte[] payload, boolean confirm);

    default Mono<Void> publishPrepared(String key, OwnedPipeline.Serializer serializer, boolean confirm, OwnedPipeline.Preparation preparation) {
        try { return publish(key, serializer.serialize(), confirm); }
        catch (Exception failure) { return Mono.error(new OwnedPipeline.Failure(500, "serialization", failure)); }
    }

    @Override
    default void close() {
        // Default no-op for tests.
    }
}
