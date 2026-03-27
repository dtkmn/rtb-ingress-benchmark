package demo.adtech;

import java.io.Closeable;

import reactor.core.publisher.Mono;

public interface BidPublisher extends Closeable {

    Mono<Void> publish(String key, byte[] payload, boolean confirm);

    @Override
    default void close() {
        // Default no-op for tests.
    }
}
