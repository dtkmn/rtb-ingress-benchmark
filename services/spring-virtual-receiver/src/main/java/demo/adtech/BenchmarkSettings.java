package demo.adtech;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class BenchmarkSettings {

    private static final Logger LOG = LoggerFactory.getLogger(BenchmarkSettings.class);

    static final String DELIVERY_MODE_CONFIRM = "confirm";
    static final String DELIVERY_MODE_ENQUEUE = "enqueue";
    static final String DELIVERY_MODE_HTTP_ONLY = "http-only";

    private final String deliveryMode;
    private final String kafkaBootstrapServers;
    private final String kafkaTopic;
    private final String kafkaAcks;
    private final int kafkaLingerMs;
    private final int kafkaBatchBytes;
    private final int kafkaRequestTimeoutMs;

    @Autowired
    public BenchmarkSettings(Environment environment) {
        this(
                environment.getProperty("benchmark.delivery.mode"),
                environment.getProperty("kafka.bootstrap.servers"),
                environment.getProperty("benchmark.kafka.topic"),
                environment.getProperty("benchmark.kafka.acks"),
                environment.getProperty("benchmark.kafka.linger.ms"),
                environment.getProperty("benchmark.kafka.batch.bytes"),
                environment.getProperty("benchmark.kafka.request.timeout.ms")
        );
    }

    private BenchmarkSettings(
            String deliveryMode,
            String kafkaBootstrapServers,
            String kafkaTopic,
            String kafkaAcks,
            String kafkaLingerMs,
            String kafkaBatchBytes,
            String kafkaRequestTimeoutMs
    ) {
        this.deliveryMode = normalizeDeliveryMode(deliveryMode);
        this.kafkaBootstrapServers = normalizeKafkaBootstrapServers(kafkaBootstrapServers);
        this.kafkaTopic = normalizeKafkaTopic(kafkaTopic);
        this.kafkaAcks = normalizeKafkaAcks(kafkaAcks);
        this.kafkaLingerMs = normalizePositiveInt(kafkaLingerMs, 10, "BENCHMARK_KAFKA_LINGER_MS");
        this.kafkaBatchBytes = normalizePositiveInt(kafkaBatchBytes, 131072, "BENCHMARK_KAFKA_BATCH_BYTES");
        this.kafkaRequestTimeoutMs = normalizePositiveInt(
                kafkaRequestTimeoutMs,
                5000,
                "BENCHMARK_KAFKA_REQUEST_TIMEOUT_MS"
        );
    }

    public static BenchmarkSettings forTests(
            String deliveryMode,
            String kafkaBootstrapServers,
            String kafkaTopic,
            String kafkaAcks
    ) {
        return new BenchmarkSettings(deliveryMode, kafkaBootstrapServers, kafkaTopic, kafkaAcks, null, null, null);
    }

    public boolean isConfirmDeliveryMode() {
        return DELIVERY_MODE_CONFIRM.equals(deliveryMode);
    }

    public boolean isHttpOnlyMode() {
        return DELIVERY_MODE_HTTP_ONLY.equals(deliveryMode);
    }

    public boolean usesKafka() {
        return !isHttpOnlyMode();
    }

    public String deliveryMode() {
        return deliveryMode;
    }

    public String kafkaBootstrapServers() {
        return kafkaBootstrapServers;
    }

    public String kafkaTopic() {
        return kafkaTopic;
    }

    public String kafkaAcks() {
        return kafkaAcks;
    }

    public int kafkaLingerMs() {
        return kafkaLingerMs;
    }

    public int kafkaBatchBytes() {
        return kafkaBatchBytes;
    }

    public int kafkaRequestTimeoutMs() {
        return kafkaRequestTimeoutMs;
    }

    private static String normalizeDeliveryMode(String raw) {
        String candidate = normalize(raw, DELIVERY_MODE_CONFIRM);
        if (DELIVERY_MODE_CONFIRM.equals(candidate)
                || DELIVERY_MODE_ENQUEUE.equals(candidate)
                || DELIVERY_MODE_HTTP_ONLY.equals(candidate)) {
            return candidate;
        }

        LOG.warn("Unknown BENCHMARK_DELIVERY_MODE={}; defaulting to {}", raw, DELIVERY_MODE_CONFIRM);
        return DELIVERY_MODE_CONFIRM;
    }

    private static String normalizeKafkaBootstrapServers(String raw) {
        return normalize(raw, "localhost:9092");
    }

    private static String normalizeKafkaTopic(String raw) {
        return normalize(raw, "bids");
    }

    private static String normalizeKafkaAcks(String raw) {
        String candidate = normalize(raw, "1");
        return switch (candidate) {
            case "0", "none" -> "0";
            case "-1", "all" -> "all";
            case "1", "leader" -> "1";
            default -> {
                LOG.warn("Unknown BENCHMARK_KAFKA_ACKS={}; defaulting to leader acknowledgements", raw);
                yield "1";
            }
        };
    }

    private static String normalize(String raw, String fallback) {
        if (raw == null) {
            return fallback;
        }

        String trimmed = raw.trim().toLowerCase();
        return trimmed.isEmpty() ? fallback : trimmed;
    }

    private static int normalizePositiveInt(String raw, int fallback, String envName) {
        if (raw == null || raw.trim().isEmpty()) {
            return fallback;
        }

        try {
            int parsed = Integer.parseInt(raw.trim());
            if (parsed > 0) {
                return parsed;
            }
        } catch (NumberFormatException ignored) {
            // fall through
        }

        LOG.warn("Ignoring invalid {}={}; defaulting to {}", envName, raw, fallback);
        return fallback;
    }
}
