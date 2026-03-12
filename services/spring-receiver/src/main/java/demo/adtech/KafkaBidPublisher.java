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
import reactor.core.publisher.Mono;

import java.util.Properties;

@Component
public class KafkaBidPublisher implements BidPublisher {

    private static final Logger LOG = LoggerFactory.getLogger(KafkaBidPublisher.class);

    private final KafkaProducer<String, byte[]> producer;
    private final BenchmarkSettings settings;

    public KafkaBidPublisher(BenchmarkSettings settings) {
        this.settings = settings;
        this.producer = settings.usesKafka() ? new KafkaProducer<>(buildProperties(settings)) : null;

        if (this.producer == null) {
            LOG.info("HTTP-only benchmark mode enabled; skipping Kafka producer initialization");
        } else {
            LOG.info(
                    "Initialized Spring receiver publisher (delivery_mode={}, acks={})",
                    settings.deliveryMode(),
                    settings.kafkaAcks()
            );
        }
    }

    @Override
    public Mono<Void> publish(String key, byte[] payload, boolean confirm) {
        return Mono.defer(() -> {
            if (producer == null) {
                return Mono.error(new PublisherUnavailableException(null));
            }

            ProducerRecord<String, byte[]> record = new ProducerRecord<>(settings.kafkaTopic(), key, payload);
            if (!confirm) {
                return Mono.fromRunnable(() -> {
                    try {
                        producer.send(record);
                    } catch (RuntimeException exception) {
                        throw new PublisherUnavailableException(exception);
                    }
                });
            }

            return Mono.create(sink -> {
                Callback callback = (metadata, exception) -> {
                    if (exception == null) {
                        sink.success();
                        return;
                    }
                    sink.error(new PublisherBackpressureException(exception));
                };

                try {
                    producer.send(record, callback);
                } catch (RuntimeException exception) {
                    sink.error(new PublisherUnavailableException(exception));
                }
            });
        });
    }

    @PreDestroy
    @Override
    public void close() {
        if (producer != null) {
            producer.close();
        }
    }

    private static Properties buildProperties(BenchmarkSettings settings) {
        Properties properties = new Properties();
        properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, settings.kafkaBootstrapServers());
        properties.put(ProducerConfig.CLIENT_ID_CONFIG, "spring-receiver");
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        properties.put(ProducerConfig.ACKS_CONFIG, settings.kafkaAcks());
        properties.put(ProducerConfig.LINGER_MS_CONFIG, Integer.toString(settings.kafkaLingerMs()));
        properties.put(ProducerConfig.BATCH_SIZE_CONFIG, Integer.toString(settings.kafkaBatchBytes()));
        properties.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, Integer.toString(settings.kafkaRequestTimeoutMs()));
        properties.put(
                ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG,
                Integer.toString(settings.kafkaRequestTimeoutMs() + Math.max(1000, settings.kafkaLingerMs()))
        );
        return properties;
    }
}
