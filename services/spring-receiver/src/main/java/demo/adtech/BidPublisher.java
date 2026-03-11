package demo.adtech;

import java.io.Closeable;
import java.util.concurrent.CompletableFuture;

public interface BidPublisher extends Closeable {

    CompletableFuture<Void> publish(String key, byte[] payload, boolean confirm);

    @Override
    default void close() {
        // Default no-op for tests.
    }
}

