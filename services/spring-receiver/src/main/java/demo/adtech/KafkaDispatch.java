package demo.adtech;

import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** An already-completed SDK future is terminal evidence even if no callback ran. */
final class KafkaDispatch {
    static void send(Producer<String, byte[]> producer, OwnedPipeline.Record record, Consumer<Throwable> completion) {
        Future<RecordMetadata> future = producer.send(new ProducerRecord<>(record.topic(), record.key(), record.value()),
                (metadata, failure) -> completion.accept(failure));
        if (!future.isDone()) return;
        try { future.get(0, TimeUnit.MILLISECONDS); completion.accept(null); }
        catch (ExecutionException failure) { completion.accept(failure.getCause()); }
        catch (CancellationException failure) { completion.accept(failure); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); completion.accept(failure); }
        catch (TimeoutException raced) { /* Pending SDK work remains owned; its callback will settle it. */ }
    }
    private KafkaDispatch() {}
}
