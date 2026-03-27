package demo.adtech;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.KStream;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@ApplicationScoped
public class TopologyProducer {

    private static final Logger LOG = Logger.getLogger(TopologyProducer.class);

    @ConfigProperty(name = "kafka-streams.topics-in")
    String inputTopic;

    @Inject
    DeadLetterQueueService dlqService;

    /**
     * This is the core logic of the Kafka Streams application.
     * It defines the "Topology" (the flow of data).
     */
    @Produces
    public Topology buildTopology() {
        StreamsBuilder builder = new StreamsBuilder();

        // 1. READ: Consume from the 'bids' topic.
        // We use the BidRequest.class for deserialization.
        KStream<String, BidRequest> stream = builder.stream(inputTopic,
                Consumed.with(org.apache.kafka.common.serialization.Serdes.String(),
                        new io.quarkus.kafka.client.serialization.ObjectMapperSerde<>(BidRequest.class))
        );

        // 2. TRANSFORM & SINK: For each message, create a BidRecord and save it.
        // This is a "terminal operation" (a sink).
        stream
                .peek((key, request) -> LOG.infof("Processing bid request: %s", request.id))
                .foreach((key, request) -> {
                    // This logic is executed for every single message
                    try {
                        saveToDatabase(request);
                    } catch (Exception e) {
                        if (dlqService.isEnabled()) {
                            LOG.errorf(e, "Failed to save bid %s to database. Sending to DLQ.", request.id);
                            dlqService.sendToDeadLetterQueue(request, e, "DATABASE_PERSIST");
                        } else {
                            LOG.errorf(e, "Failed to save bid %s to database. DLQ disabled.", request.id);
                        }
                    }
                });

        return builder.build();
    }

    /**
     * This helper method wraps the database write in a transaction.
     * This is CRITICAL for Panache.
     */
    @Transactional
    void saveToDatabase(BidRequest request) {
        BidRecord record = new BidRecord(request);
        // Panache.withTransaction will run this block in a new transaction.
        // This is a common pattern for handling writes from a stream.
        record.persist();
    }
}
