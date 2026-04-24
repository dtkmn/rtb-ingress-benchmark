package demo.adtech;

import jakarta.annotation.PreDestroy;
import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;

@Component
public class KafkaBidPublisher implements BidPublisher {

    private static final Logger LOG = LoggerFactory.getLogger(KafkaBidPublisher.class);

    private final List<KafkaProducer<String, byte[]>> producers;
    private final BenchmarkSettings settings;

    public KafkaBidPublisher(BenchmarkSettings settings) {
        this.settings = settings;
        this.producers = settings.usesKafka() ? buildProducerPool(settings) : List.of();

        if (this.producers.isEmpty()) {
            LOG.info("HTTP-only benchmark mode enabled; skipping Kafka producer initialization");
        } else {
            LOG.info(
                    "Initialized Spring virtual receiver publisher (delivery_mode={}, topic={}, acks={}, producer_pool_size={}, retries={}, retry_backoff_ms={})",
                    settings.deliveryMode(),
                    settings.kafkaTopic(),
                    settings.kafkaAcks(),
                    settings.kafkaProducerPoolSize(),
                    settings.kafkaRetries(),
                    settings.kafkaRetryBackoffMs()
            );
        }
    }

    @Override
    public CompletableFuture<Void> publish(String key, byte[] payload, boolean confirm) {
        if (producers.isEmpty()) {
            CompletableFuture<Void> failed = new CompletableFuture<>();
            failed.completeExceptionally(new PublisherUnavailableException(null));
            return failed;
        }

        KafkaProducer<String, byte[]> producer = selectProducer(key);
        ProducerRecord<String, byte[]> record = new ProducerRecord<>(settings.kafkaTopic(), key, payload);

        if (!confirm) {
            try {
                producer.send(record);
                return CompletableFuture.completedFuture(null);
            } catch (RuntimeException exception) {
                CompletableFuture<Void> failed = new CompletableFuture<>();
                failed.completeExceptionally(new PublisherUnavailableException(exception));
                return failed;
            }
        }

        CompletableFuture<Void> delivery = new CompletableFuture<>();
        Callback callback = (metadata, exception) -> {
            if (exception == null) {
                delivery.complete(null);
                return;
            }
            delivery.completeExceptionally(new PublisherBackpressureException(exception));
        };

        try {
            producer.send(record, callback);
        } catch (RuntimeException exception) {
            delivery.completeExceptionally(new PublisherUnavailableException(exception));
        }

        return delivery;
    }

    @PreDestroy
    @Override
    public void close() {
        for (KafkaProducer<String, byte[]> producer : producers) {
            producer.close();
        }
    }

    private KafkaProducer<String, byte[]> selectProducer(String key) {
        int index = Math.floorMod(Objects.hashCode(key), producers.size());
        return producers.get(index);
    }

    private static List<KafkaProducer<String, byte[]>> buildProducerPool(BenchmarkSettings settings) {
        ArrayList<KafkaProducer<String, byte[]>> producerPool = new ArrayList<>(settings.kafkaProducerPoolSize());
        for (int index = 0; index < settings.kafkaProducerPoolSize(); index++) {
            producerPool.add(new KafkaProducer<>(buildProperties(settings, index)));
        }
        return List.copyOf(producerPool);
    }

    private static Properties buildProperties(BenchmarkSettings settings, int producerIndex) {
        Properties properties = new Properties();
        properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, settings.kafkaBootstrapServers());
        properties.put(ProducerConfig.CLIENT_ID_CONFIG, "spring-virtual-receiver-" + (producerIndex + 1));
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        properties.put(ProducerConfig.ACKS_CONFIG, settings.kafkaAcks());
        properties.put(ProducerConfig.LINGER_MS_CONFIG, Integer.toString(settings.kafkaLingerMs()));
        properties.put(ProducerConfig.BATCH_SIZE_CONFIG, Integer.toString(settings.kafkaBatchBytes()));
        properties.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, Integer.toString(settings.kafkaRequestTimeoutMs()));
        properties.put(ProducerConfig.RETRIES_CONFIG, Integer.toString(settings.kafkaRetries()));
        properties.put(ProducerConfig.RETRY_BACKOFF_MS_CONFIG, Integer.toString(settings.kafkaRetryBackoffMs()));
        properties.put(
                ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG,
                Integer.toString(computeDeliveryTimeoutMs(settings))
        );
        return properties;
    }

    private static int computeDeliveryTimeoutMs(BenchmarkSettings settings) {
        long timeout = (long) settings.kafkaRequestTimeoutMs() * (settings.kafkaRetries() + 1L)
                + (long) settings.kafkaRetryBackoffMs() * settings.kafkaRetries()
                + Math.max(settings.kafkaLingerMs(), 1000L);
        return (int) Math.min(Integer.MAX_VALUE, timeout);
    }
}
