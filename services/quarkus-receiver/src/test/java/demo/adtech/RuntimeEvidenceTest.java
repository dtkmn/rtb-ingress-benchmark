package demo.adtech;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.Map;
import java.util.Properties;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeEvidenceTest {
    @TempDir Path root;
    @Test void profileRejectsUnsupportedAndUnknownValues() {
        assertEquals("controlled",RuntimeEvidence.requireControlled(null));
        assertEquals("controlled",RuntimeEvidence.requireControlled(" CONTROLLED "));
        for (String profile:new String[]{"operational","garbage",""}) assertThrows(IllegalArgumentException.class,()->RuntimeEvidence.requireControlled(profile));
    }
    @Test void liveHeapUsesRuntimeReadback() {
        var heap=RuntimeEvidence.heap(false); assertEquals(Runtime.getRuntime().maxMemory(),heap.get("max_bytes"));
        assertNotNull(heap.get("gc"));
        long vmMaximum=Long.parseLong(java.lang.management.ManagementFactory.getPlatformMXBean(
                com.sun.management.HotSpotDiagnosticMXBean.class).getVMOption("MaxHeapSize").getValue());
        assertEquals(vmMaximum,heap.get("vm_max_heap_bytes"));
        assertTrue(((Long)heap.get("max_bytes"))>0 && ((Long)heap.get("max_bytes"))<=vmMaximum);
        assertTrue(heap.get("vm_max_heap_evidence").toString().contains("getVMOption(MaxHeapSize)"));
    }
    @Test void nativeMetadataDoesNotInventOrQueryHotSpotMaximum() {
        var heap=RuntimeEvidence.heap(true);
        assertNull(heap.get("vm_max_heap_bytes"));
        assertTrue(heap.get("vm_max_heap_evidence").toString().contains("not queried"));
        assertEquals(Runtime.getRuntime().maxMemory(),heap.get("max_bytes"));
    }
    @Test void v2KernelControlsAndUnlimitedValues() throws Exception {
        Files.writeString(root.resolve("memory.max"),"805306368\n"); Files.writeString(root.resolve("cpu.max"),"200000 100000\n");
        Files.writeString(root.resolve("cpuset.cpus.effective"),"0-1\n");
        var cg=RuntimeEvidence.readCgroup(root); assertEquals("2",cg.get("version")); assertEquals(805306368L,cg.get("memory_max_bytes"));
        assertEquals(200000L,cg.get("cpu_quota_us")); assertEquals(100000L,cg.get("cpu_period_us")); assertEquals("0-1",cg.get("cpuset_cpus_effective"));
        Files.writeString(root.resolve("memory.max"),"max");Files.writeString(root.resolve("cpu.max"),"max 100000");
        cg=RuntimeEvidence.readCgroup(root);assertNull(cg.get("memory_max_bytes"));assertNull(cg.get("cpu_quota_us"));assertEquals(100000L,cg.get("cpu_period_us"));
    }
    @Test void v1FallbackAndUnreadableControls() throws Exception {
        assertNull(RuntimeEvidence.readCgroup(root).get("version"));
        Files.createDirectories(root.resolve("memory"));Files.createDirectories(root.resolve("cpu"));Files.createDirectories(root.resolve("cpuset"));
        Files.writeString(root.resolve("memory/memory.limit_in_bytes"),"805306368");
        Files.writeString(root.resolve("cpu/cpu.cfs_quota_us"),"-1"); Files.writeString(root.resolve("cpu/cpu.cfs_period_us"),"100000");
        Files.writeString(root.resolve("cpuset/cpuset.effective_cpus"),"2,4");
        var cg=RuntimeEvidence.readCgroup(root);assertEquals("1",cg.get("version"));assertEquals(805306368L,cg.get("memory_max_bytes"));
        assertNull(cg.get("cpu_quota_us"));assertEquals("2,4",cg.get("cpuset_cpus_effective"));
    }
    @Test void malformedAndOversizedKernelDataDoesNotInventLimits() throws Exception {
        Files.writeString(root.resolve("memory.max"),"9".repeat(4097));Files.writeString(root.resolve("cpu.max"),"garbage");
        var cg=RuntimeEvidence.readCgroup(root);assertNull(cg.get("memory_max_bytes"));assertNull(cg.get("cpu_quota_us"));assertNull(cg.get("cpu_period_us"));
    }
    @SuppressWarnings("unchecked")
    @Test void producerReadbackAppliesZeroAndDistinctNonDefaults() {
        Properties properties=new Properties();properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,"localhost:9392");
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,StringSerializer.class);properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,ByteArraySerializer.class);
        properties.put(ProducerConfig.ACKS_CONFIG,"1");properties.put(ProducerConfig.LINGER_MS_CONFIG,0);properties.put(ProducerConfig.BATCH_SIZE_CONFIG,16384);
        properties.put(ProducerConfig.RETRIES_CONFIG,0);properties.put(ProducerConfig.RETRY_BACKOFF_MS_CONFIG,117);properties.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG,4321);
        properties.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG,5321);properties.put(ProducerConfig.ENABLE_METRICS_PUSH_CONFIG,false);
        var snapshot=new ProducerEvidence(properties,2).snapshot(2); assertEquals(2,snapshot.get("instances"));
        var controls=(Map<String,Map<String,Object>>)snapshot.get("controls");
        assertEquals(0L,controls.get("linger_ms").get("effective"));assertEquals(0,controls.get("linger_ms").get("applied"));
        assertEquals(117L,controls.get("retry_backoff_ms").get("effective"));assertEquals(4321,controls.get("request_timeout_ms").get("effective"));
        assertEquals(false,controls.get("enable_idempotence").get("effective"));assertEquals(false,controls.get("metrics_push").get("effective"));
        assertEquals(33554432L,controls.get("buffer_memory_bytes").get("effective"));
        assertEquals("unsupported",controls.get("buffer_memory_bytes").get("status"));assertNull(controls.get("buffer_memory_bytes").get("applied"));
        var absent=(Map<String,Map<String,Object>>)ProducerEvidence.absent(2).get("controls");
        assertEquals("not_applicable",absent.get("linger_ms").get("status"));assertNull(absent.get("linger_ms").get("effective"));
    }
}
