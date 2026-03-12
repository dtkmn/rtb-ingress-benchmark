package demo.adtech;

import io.quarkus.kafka.client.serialization.ObjectMapperSerializer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

@ApplicationScoped
public class BidPublisher {

    private static final Logger LOG = Logger.getLogger(BidPublisher.class);

    @Inject
    BenchmarkSettings benchmarkSettings;

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

    @ConfigProperty(name = "benchmark.kafka.send.buffer.bytes", defaultValue = "131072")
    int sendBufferBytes;

    @ConfigProperty(name = "benchmark.kafka.receive.buffer.bytes", defaultValue = "131072")
    int receiveBufferBytes;

    private KafkaProducer<String, BidRequest> producer;

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

        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ObjectMapperSerializer.class.getName());
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
        props.put(ProducerConfig.SEND_BUFFER_CONFIG, sendBufferBytes);
        props.put(ProducerConfig.RECEIVE_BUFFER_CONFIG, receiveBufferBytes);

        producer = new KafkaProducer<>(props);
        LOG.infof(
                "Initialized Kafka producer for topic %s (delivery_mode=%s, acks=%s, retries=%d, retry_backoff_ms=%d)",
                topic,
                benchmarkSettings.deliveryMode(),
                acks,
                effectiveRetries,
                effectiveRetryBackoffMs
        );
    }

    public CompletionStage<Void> publish(BidRequest request) {
        if (benchmarkSettings.isHttpOnlyMode()) {
            return CompletableFuture.completedFuture(null);
        }

        if (producer == null) {
            return CompletableFuture.failedStage(new IllegalStateException("Kafka producer unavailable"));
        }

        ProducerRecord<String, BidRequest> record = new ProducerRecord<>(topic, request.id, request);
        CompletableFuture<Void> delivery = new CompletableFuture<>();

        try {
            producer.send(record, (metadata, exception) -> {
                if (exception != null) {
                    if (benchmarkSettings.isConfirmDeliveryMode()) {
                        delivery.completeExceptionally(exception);
                    } else {
                        LOG.error("Kafka enqueue error", exception);
                    }
                    return;
                }

                delivery.complete(null);
            });
        } catch (Exception e) {
            return CompletableFuture.failedStage(e);
        }

        if (!benchmarkSettings.isConfirmDeliveryMode()) {
            return CompletableFuture.completedFuture(null);
        }

        return delivery;
    }

    @PreDestroy
    void close() {
        if (producer != null) {
            producer.close();
        }
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
}
