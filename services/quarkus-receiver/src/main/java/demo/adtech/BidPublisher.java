package demo.adtech;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

@ApplicationScoped
public class BidPublisher {

    private static final Logger LOG = Logger.getLogger(BidPublisher.class);

    @Inject
    BenchmarkSettings benchmarkSettings;

    @Inject
    ObjectMapper objectMapper;

    @ConfigProperty(name = "kafka.bootstrap.servers", defaultValue = "localhost:9092")
    String bootstrapServers;

    @ConfigProperty(name = "benchmark.kafka.topic", defaultValue = "bids")
    String topic;

    @ConfigProperty(name = "benchmark.kafka.acks", defaultValue = "1")
    String acks;

    @ConfigProperty(name = "benchmark.kafka.linger.ms", defaultValue = "10")
    int lingerMs;

    @ConfigProperty(name = "benchmark.kafka.batch.bytes", defaultValue = "131072")
    int batchBytes;

    @ConfigProperty(name = "benchmark.kafka.request.timeout.ms", defaultValue = "5000")
    int requestTimeoutMs;

    @ConfigProperty(name = "benchmark.kafka.retries", defaultValue = "5")
    int retries;

    @ConfigProperty(name = "benchmark.kafka.retry.backoff.ms", defaultValue = "100")
    int retryBackoffMs;

    @ConfigProperty(name = "benchmark.kafka.producer.pool.size", defaultValue = "1")
    int producerPoolSize;

    @ConfigProperty(name = "benchmark.kafka.send.buffer.bytes", defaultValue = "-1")
    int sendBufferBytes;

    @ConfigProperty(name = "benchmark.kafka.receive.buffer.bytes", defaultValue = "-1")
    int receiveBufferBytes;

    private List<KafkaProducer<String, byte[]>> producers = List.of();

    @PostConstruct
    void init() {
        if (benchmarkSettings.isHttpOnlyMode()) {
            LOG.info("HTTP-only benchmark mode enabled; skipping Kafka producer initialization");
            return;
        }

        int effectiveRetries = sanitizeNonNegativeInt("BENCHMARK_KAFKA_RETRIES", retries, 5);
        int effectiveRetryBackoffMs = sanitizeNonNegativeInt(
                "BENCHMARK_KAFKA_RETRY_BACKOFF_MS",
                retryBackoffMs,
                100
        );
        int effectiveProducerPoolSize = sanitizePositiveInt(
                "BENCHMARK_KAFKA_PRODUCER_POOL_SIZE",
                producerPoolSize,
                1
        );

        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, acks);
        props.put(ProducerConfig.LINGER_MS_CONFIG, lingerMs);
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, batchBytes);
        props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, requestTimeoutMs);
        props.put(ProducerConfig.RETRIES_CONFIG, effectiveRetries);
        props.put(ProducerConfig.RETRY_BACKOFF_MS_CONFIG, effectiveRetryBackoffMs);
        props.put(
                ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG,
                computeDeliveryTimeoutMs(requestTimeoutMs, lingerMs, effectiveRetries, effectiveRetryBackoffMs)
        );
        applyOptionalBufferConfig(props, ProducerConfig.SEND_BUFFER_CONFIG, sendBufferBytes);
        applyOptionalBufferConfig(props, ProducerConfig.RECEIVE_BUFFER_CONFIG, receiveBufferBytes);

        ArrayList<KafkaProducer<String, byte[]>> initializedProducers = new ArrayList<>(effectiveProducerPoolSize);
        for (int index = 0; index < effectiveProducerPoolSize; index++) {
            Properties producerProps = new Properties();
            producerProps.putAll(props);
            producerProps.put(ProducerConfig.CLIENT_ID_CONFIG, "quarkus-receiver-" + (index + 1));
            initializedProducers.add(new KafkaProducer<>(producerProps));
        }
        producers = List.copyOf(initializedProducers);
        LOG.infof(
                "Initialized Kafka producer pool for topic %s (delivery_mode=%s, acks=%s, producer_pool_size=%d, retries=%d, retry_backoff_ms=%d)",
                topic,
                benchmarkSettings.deliveryMode(),
                acks,
                effectiveProducerPoolSize,
                effectiveRetries,
                effectiveRetryBackoffMs
        );
    }

    public CompletionStage<Void> publish(BidRequest request) {
        if (benchmarkSettings.isHttpOnlyMode()) {
            return CompletableFuture.completedFuture(null);
        }

        if (producers.isEmpty()) {
            return CompletableFuture.failedStage(new IllegalStateException("Kafka producer unavailable"));
        }

        byte[] payload;
        try {
            payload = objectMapper.writeValueAsBytes(request);
        } catch (JsonProcessingException exception) {
            return CompletableFuture.failedStage(exception);
        }

        KafkaProducer<String, byte[]> producer = selectProducer(request.id);
        ProducerRecord<String, byte[]> record = new ProducerRecord<>(topic, request.id, payload);

        try {
            if (!benchmarkSettings.isConfirmDeliveryMode()) {
                producer.send(record);
                return CompletableFuture.completedFuture(null);
            }
        } catch (Exception e) {
            return CompletableFuture.failedStage(e);
        }

        CompletableFuture<Void> delivery = new CompletableFuture<>();
        try {
            producer.send(record, (metadata, exception) -> {
                if (exception != null) {
                    delivery.completeExceptionally(exception);
                    return;
                }

                delivery.complete(null);
            });
            return delivery;
        } catch (Exception exception) {
            return CompletableFuture.failedStage(exception);
        }
    }

    @PreDestroy
    void close() {
        for (KafkaProducer<String, byte[]> producer : producers) {
            producer.close();
        }
    }

    private KafkaProducer<String, byte[]> selectProducer(String key) {
        int index = Math.floorMod(Objects.hashCode(key), producers.size());
        return producers.get(index);
    }

    private static int computeDeliveryTimeoutMs(int requestTimeoutMs, int lingerMs, int retries, int retryBackoffMs) {
        long timeout = (long) requestTimeoutMs * (retries + 1L)
                + (long) retryBackoffMs * retries
                + Math.max(lingerMs, 1000L);
        return (int) Math.min(Integer.MAX_VALUE, timeout);
    }

    private int sanitizeNonNegativeInt(String envName, int rawValue, int fallback) {
        if (rawValue >= 0) {
            return rawValue;
        }

        LOG.warnf("Ignoring invalid %s=%d; defaulting to %d", envName, rawValue, fallback);
        return fallback;
    }

    private int sanitizePositiveInt(String envName, int rawValue, int fallback) {
        if (rawValue > 0) {
            return rawValue;
        }

        LOG.warnf("Ignoring invalid %s=%d; defaulting to %d", envName, rawValue, fallback);
        return fallback;
    }

    private static void applyOptionalBufferConfig(Properties props, String key, int value) {
        if (value >= 0) {
            props.put(key, value);
        }
    }
}
