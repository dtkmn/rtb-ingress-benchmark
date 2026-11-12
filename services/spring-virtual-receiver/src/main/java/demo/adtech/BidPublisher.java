package demo.adtech;

import java.io.Closeable;
import java.util.concurrent.CompletableFuture;

public interface BidPublisher extends Closeable {

    CompletableFuture<Void> publish(String key, byte[] payload, boolean confirm);

    default CompletableFuture<Void> publishPrepared(String key, OwnedPipeline.Serializer serializer, boolean confirm, OwnedPipeline.Preparation preparation) {
        try { return publish(key, serializer.serialize(), confirm); }
        catch (Exception failure) { return CompletableFuture.failedFuture(new OwnedPipeline.Failure(500, "serialization", failure)); }
    }

    @Override
    default void close() {
        // Default no-op for tests.
    }
}
