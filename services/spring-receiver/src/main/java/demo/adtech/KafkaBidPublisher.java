package demo.adtech;

import jakarta.annotation.PreDestroy;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

@Component
public class KafkaBidPublisher implements BidPublisher {

    private static final Logger LOG = LoggerFactory.getLogger(KafkaBidPublisher.class);

    private final List<KafkaProducer<String, byte[]>> producers;
    private final BenchmarkSettings settings;
    private final OwnedPipeline pipeline;
    private ProducerEvidence producerEvidence;
    private final ConformanceAdapter conformance;

    public KafkaBidPublisher(BenchmarkSettings settings) {
        this.settings = settings;
        RuntimeEvidence.requireControlled(System.getenv("BENCHMARK_TELEMETRY_PROFILE"));
        if (settings.isConfirmDeliveryMode() && "0".equals(settings.kafkaAcks())) throw new IllegalArgumentException("confirm requires acknowledgements 1 or all");
        this.conformance=ConformanceAdapter.fromEnvironment(settings.deliveryMode());
        this.producers = settings.usesKafka() && conformance==null ? buildProducerPool(settings) : List.of();
        this.pipeline = new OwnedPipeline(settings.kafkaTopic(), conformance!=null && settings.usesKafka()?conformance:producers.isEmpty() ? null : new OwnedPipeline.Client() {
            @Override public void send(OwnedPipeline.Record record, java.util.function.Consumer<Throwable> completion) {
                KafkaDispatch.send(selectProducer(record.key()), record, completion);
            }
            @Override public void close() {
                for (KafkaProducer<String, byte[]> producer : producers) producer.close(java.time.Duration.ZERO);
            }
        }, Math.max(1, producers.size()));
        if (conformance!=null) conformance.bind(pipeline);

        if (this.producers.isEmpty()) {
            LOG.info("HTTP-only or explicit conformance mode enabled; skipping Kafka SDK producer initialization");
        } else {
            LOG.info(
                    "Initialized Spring receiver publisher (delivery_mode={}, topic={}, acks={}, producer_pool_size={}, retries={}, retry_backoff_ms={})",
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
    public Mono<Void> publish(String key, byte[] payload, boolean confirm) {
        return publishPrepared(key, () -> payload, confirm, null);
    }
    @Override
    public Mono<Void> publishPrepared(String key, OwnedPipeline.Serializer serializer, boolean confirm, OwnedPipeline.Preparation preparation) {
        return Mono.fromFuture(pipeline.publish(key, serializer, confirm, preparation), true);
    }
    public OwnedPipeline pipeline() { return pipeline; }
    public Map<String,Object> producerRuntime() { var evidence=producerEvidence==null?ProducerEvidence.absent(settings.kafkaProducerPoolSize()):producerEvidence.snapshot(producers.size());return conformance==null?evidence:conformance.transportMetadata(evidence); }
    public ConformanceAdapter conformance() { return conformance; }
    @org.springframework.context.event.EventListener(org.springframework.context.event.ContextClosedEvent.class)
    public void onShutdown() { close(); }
    @PreDestroy
    @Override
    public void close() {
        try { pipeline.close(); }
        finally {
            try { LOG.info("BENCHMARK_DRAIN {}", tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(pipeline.drainEvidence())); }
            catch (tools.jackson.core.JacksonException failure) { LOG.error("Cannot encode final drain evidence", failure); }
        }
    }

    private KafkaProducer<String, byte[]> selectProducer(String key) {
        int index = Math.floorMod(Objects.hashCode(key), producers.size());
        return producers.get(index);
    }

    private List<KafkaProducer<String, byte[]>> buildProducerPool(BenchmarkSettings settings) {
        ArrayList<KafkaProducer<String, byte[]>> producerPool = new ArrayList<>(settings.kafkaProducerPoolSize());
        for (int index = 0; index < settings.kafkaProducerPoolSize(); index++) {
            Properties properties=buildProperties(settings,index);
            if (index==0) producerEvidence=new ProducerEvidence(properties,settings.kafkaProducerPoolSize());
            producerPool.add(new KafkaProducer<>(properties));
        }
        return List.copyOf(producerPool);
    }

    static Properties buildProperties(BenchmarkSettings settings, int producerIndex) {
        Properties properties = new Properties();
        properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, settings.kafkaBootstrapServers());
        properties.put(ProducerConfig.CLIENT_ID_CONFIG, "spring-receiver-" + (producerIndex + 1));
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        properties.put(ProducerConfig.ENABLE_METRICS_PUSH_CONFIG, false);
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
