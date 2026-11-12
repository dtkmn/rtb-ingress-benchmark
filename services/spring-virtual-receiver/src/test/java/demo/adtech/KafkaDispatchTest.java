package demo.adtech;

import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.*;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class KafkaDispatchTest {
    @Test void failedImmediateSdkFutureWithoutCallbackSettlesOwnedAttempt() throws Exception {
        for (boolean confirm : new boolean[]{false,true}) {
            MockProducer<String,byte[]> producer = new MockProducer<String,byte[]>(true, null, new StringSerializer(), new ByteArraySerializer()) {
                @Override public synchronized Future<RecordMetadata> send(ProducerRecord<String,byte[]> record, Callback callback) {
                    return CompletableFuture.failedFuture(new IllegalStateException("already failed SDK future; callback absent"));
                }
            };
            OwnedPipeline pipeline = new OwnedPipeline("topic", new OwnedPipeline.Client() {
                @Override public void send(OwnedPipeline.Record record, java.util.function.Consumer<Throwable> completion) { KafkaDispatch.send(producer, record, completion); }
                @Override public void close() { producer.close(Duration.ZERO); }
            }, 1, 2, 100, 2, Duration.ofSeconds(1), Duration.ofMillis(20));
            try {
                CompletableFuture<Void> response = pipeline.publish("key", () -> new byte[]{1}, confirm, null);
                if (confirm) assertThrows(ExecutionException.class, () -> response.get(2, TimeUnit.SECONDS));
                else response.get(2, TimeUnit.SECONDS);
                long until = System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
                while (((Number)pipeline.state().get("client_failed")).longValue()!=1 && System.nanoTime()<until) java.util.concurrent.locks.LockSupport.parkNanos(100_000);
                assertEquals(1L, pipeline.state().get("client_failed")); assertEquals(0L, pipeline.state().get("outstanding_bytes"));
                assertEquals(1L, pipeline.state().get("admitted")); assertEquals(0L, pipeline.state().get("admission_rejected"));
            } finally { pipeline.close(); }
        }
    }
    @Test void normalCallbackAndCompletedFutureDoNotDoubleCount() throws Exception {
        MockProducer<String,byte[]> producer = new MockProducer<String,byte[]>(true, null, new StringSerializer(), new ByteArraySerializer());
        try (OwnedPipeline pipeline = new OwnedPipeline("topic", new OwnedPipeline.Client() {
            @Override public void send(OwnedPipeline.Record record, java.util.function.Consumer<Throwable> completion) { KafkaDispatch.send(producer, record, completion); }
            @Override public void close() { producer.close(Duration.ZERO); }
        },1,2,100,2,Duration.ofSeconds(1),Duration.ofMillis(20))) {
            pipeline.publish("key", () -> new byte[]{1}, true, null).get(2, TimeUnit.SECONDS);
            assertEquals(1L, pipeline.state().get("client_succeeded")); assertEquals(0L, pipeline.state().get("client_failed"));
        }
    }
}
