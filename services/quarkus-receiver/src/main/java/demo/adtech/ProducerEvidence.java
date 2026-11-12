package demo.adtech;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.utils.AppInfoParser;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/** Captures the exact constructor arguments and public SDK normalization once at startup. */
public final class ProducerEvidence {
    private final Map<String,Object> applied;
    private final Map<String,Object> normalized;
    private final int requestedPool;
    public ProducerEvidence(Properties properties, int requestedPool) {
        this.requestedPool=requestedPool;
        applied=new LinkedHashMap<>(); properties.forEach((key,value)->applied.put(key.toString(),value));
        normalized=new LinkedHashMap<>(new ProducerConfig(properties).values());
    }
    public static Map<String,Object> absent(int requestedPool) { return build(null,0,requestedPool); }
    public Map<String,Object> snapshot(int instances) { return build(this,instances,requestedPool); }
    private static Object requested(String control,String env) {
        if (control.equals("acks")) return System.getenv(env)==null?"1":System.getenv(env);
        long fallback=switch(control) { case "linger_ms"->10; case "batch_bytes"->131072; case "retries"->5; case "retry_backoff_ms"->100; default->5000; };
        return RuntimeEvidence.requestedInt(env,fallback);
    }
    private static Map<String,Object> build(ProducerEvidence source,int instances,int requestedPool) {
        Map<String,Object> controls=new LinkedHashMap<>();
        String[][] knobs={
                {"acks",ProducerConfig.ACKS_CONFIG,"BENCHMARK_KAFKA_ACKS"},
                {"linger_ms",ProducerConfig.LINGER_MS_CONFIG,"BENCHMARK_KAFKA_LINGER_MS"},
                {"batch_bytes",ProducerConfig.BATCH_SIZE_CONFIG,"BENCHMARK_KAFKA_BATCH_BYTES"},
                {"retries",ProducerConfig.RETRIES_CONFIG,"BENCHMARK_KAFKA_RETRIES"},
                {"retry_backoff_ms",ProducerConfig.RETRY_BACKOFF_MS_CONFIG,"BENCHMARK_KAFKA_RETRY_BACKOFF_MS"},
                {"request_timeout_ms",ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG,"BENCHMARK_KAFKA_REQUEST_TIMEOUT_MS"},
                {"delivery_timeout_ms",ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG,""},
                {"enable_idempotence",ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG,""},
                {"max_in_flight_requests",ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION,""},
                {"max_block_ms",ProducerConfig.MAX_BLOCK_MS_CONFIG,""},
                {"buffer_memory_bytes",ProducerConfig.BUFFER_MEMORY_CONFIG,""},
                {"compression_type",ProducerConfig.COMPRESSION_TYPE_CONFIG,""},
                {"metrics_push",ProducerConfig.ENABLE_METRICS_PUSH_CONFIG,""},
                {"metrics_recording_level",ProducerConfig.METRICS_RECORDING_LEVEL_CONFIG,""}};
        for (String[] knob:knobs) {
            Object requested=knob[2].isEmpty()?null:requested(knob[0],knob[2]);
            Object argument=source==null?null:source.applied.get(knob[1]);
            Object effective=source==null?null:source.normalized.get(knob[1]);
            controls.put(knob[0],RuntimeEvidence.object("status",source==null?"not_applicable":(source.applied.containsKey(knob[1])?"applied":"unsupported"),"requested",requested,
                    "applied",argument,"effective",effective,"evidence",source==null?"HTTP-only: no producer constructed":
                    "same KafkaProducer constructor properties; public ProducerConfig.values() normalization including SDK defaults, not private producer internals"));
        }
        controls.put("producer_pool_size",RuntimeEvidence.object("status",source==null?"not_applicable":"applied",
                "requested",RuntimeEvidence.requestedInt("BENCHMARK_KAFKA_PRODUCER_POOL_SIZE",1),"applied",source==null?null:requestedPool,
                "effective",source==null?null:instances,"evidence","actual application-owned KafkaProducer list size"));
        return RuntimeEvidence.object("client","org.apache.kafka.clients.producer.KafkaProducer","version",AppInfoParser.getVersion(),
                "instances",instances,"controls",controls,"sdk_metrics_note","Kafka internal correctness/client metrics remain; controlled disables external metrics push");
    }
}
