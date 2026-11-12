package demo.adtech;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Read-only, fixed-path evidence collected only outside the measured request route. */
public final class RuntimeEvidence {
    private RuntimeEvidence() { }
    public static String requireControlled(String requested) {
        String profile = requested == null ? "controlled" : requested.trim().toLowerCase(java.util.Locale.ROOT);
        if (!"controlled".equals(profile)) throw new IllegalArgumentException(
                "BENCHMARK_TELEMETRY_PROFILE supports only controlled in this build; operational requires a separately verified build");
        return profile;
    }
    public static Object requestedInt(String name,long fallback) {
        String value=System.getenv(name); if (value==null) return fallback;
        try { return Long.parseLong(value.trim()); } catch (NumberFormatException invalid) { return value.length()>128?value.substring(0,128):value; }
    }
    public static Map<String,Object> object(Object... pairs) {
        Map<String,Object> result = new LinkedHashMap<>();
        for (int i=0; i<pairs.length; i+=2) result.put((String)pairs[i],pairs[i+1]);
        return result;
    }
    public static Map<String,Object> snapshot(String lane, String mode, Map<String,Object> telemetry,
            Map<String,Object> parallelism, Map<String,Object> producer, OwnedPipeline pipeline, boolean nativeImage) {
        return object("schema_version",1,"lane",lane,"scope","process","delivery_mode",mode,
                "telemetry",telemetry,"runtime",object("pid",ProcessHandle.current().pid(),"kind",nativeImage?"native":"jvm",
                "version",System.getProperty("java.runtime.version",System.getProperty("java.version")),
                "http_parallelism",parallelism,"heap",heap(nativeImage),"cgroup",cgroup()),
                "producer",producer,"pipeline",pipeline.state());
    }
    static Map<String,Object> heap(boolean nativeImage) {
        List<String> gc = new ArrayList<>(); String evidence="Runtime.maxMemory and ManagementFactory GC MXBeans";
        try { ManagementFactory.getGarbageCollectorMXBeans().forEach(bean -> gc.add(bean.getName())); }
        catch (UnsupportedOperationException | LinkageError failure) { evidence+="; GC names unavailable on this runtime"; }
        Long vmMaximum=null; String vmEvidence;
        if (nativeImage) {
            vmEvidence="not applicable: native executable; HotSpotDiagnosticMXBean is not queried";
        } else {
            try {
                var diagnostic=ManagementFactory.getPlatformMXBean(com.sun.management.HotSpotDiagnosticMXBean.class);
                if (diagnostic==null) vmEvidence="HotSpotDiagnosticMXBean unavailable; no JVM maximum inferred";
                else {
                    var option=diagnostic.getVMOption("MaxHeapSize");
                    vmMaximum=Long.parseLong(option.getValue());
                    vmEvidence="public running HotSpotDiagnosticMXBean.getVMOption(MaxHeapSize); origin="+option.getOrigin();
                }
            } catch (RuntimeException | LinkageError unavailable) {
                vmEvidence="HotSpot MaxHeapSize API unavailable ("+unavailable.getClass().getSimpleName()+"); no JVM maximum inferred";
            }
        }
        return object("max_bytes",Runtime.getRuntime().maxMemory(),"vm_max_heap_bytes",vmMaximum,
                "vm_max_heap_evidence",vmEvidence,"gc",gc.isEmpty()?null:gc,
                "evidence",evidence+"; max_bytes is usable runtime heap, separate from the JVM MaxHeapSize flag");
    }
    public static Map<String,Object> controlledTelemetry(String evidence) {
        return object("profile","controlled","access_log",false,"tracing",false,"trace_export",false,
                "body_size_metrics",false,"request_latency_histogram",false,"evidence",evidence,
                "correctness_counters",true,"supported_profiles",List.of("controlled"));
    }
    public static Map<String,Object> cgroup() {
        if (!System.getProperty("os.name","").equalsIgnoreCase("Linux")) return unavailableCgroup("cgroup kernel files unavailable on this OS");
        return readCgroup(Path.of("/sys/fs/cgroup"));
    }
    // Package-private fixed-root seam permits deterministic tests; no HTTP path input is accepted.
    static Map<String,Object> readCgroup(Path root) {
        Map<String,Object> raw = new LinkedHashMap<>();
        String memory=read(root.resolve("memory.max")); String cpu=read(root.resolve("cpu.max"));
        String cpuset=read(root.resolve("cpuset.cpus.effective"));
        String version;
        Long quota=null,period=null;
        if (memory!=null || cpu!=null || Files.exists(root.resolve("cgroup.controllers"))) {
            version="2"; raw.put("memory.max",memory); raw.put("cpu.max",cpu); raw.put("cpuset.cpus.effective",cpuset);
            if (cpu!=null) { String[] parts=cpu.split("\\s+"); if (parts.length==2) { quota=positive(parts[0]); period=positive(parts[1]); } }
        } else {
            version="1";
            memory=read(root.resolve("memory/memory.limit_in_bytes"));
            String quotaRaw=read(root.resolve("cpu/cpu.cfs_quota_us")); String periodRaw=read(root.resolve("cpu/cpu.cfs_period_us"));
            cpuset=read(root.resolve("cpuset/cpuset.effective_cpus"));
            raw.put("memory.limit_in_bytes",memory); raw.put("cpu.cfs_quota_us",quotaRaw); raw.put("cpu.cfs_period_us",periodRaw);
            if (cpuset==null) cpuset=read(root.resolve("cpuset/cpuset.cpus")); raw.put("cpuset.effective_cpus",cpuset);
            quota=positive(quotaRaw); period=positive(periodRaw);
            if (memory==null && quotaRaw==null && cpuset==null) return unavailableCgroup("fixed cgroup v2/v1 kernel paths unreadable; no effective limits inferred");
        }
        return object("version",version,"memory_max_bytes",positive(memory),"cpu_quota_us",quota,"cpu_period_us",period,
                "cpuset_cpus_effective",cpuset,"raw",raw,"evidence","read-only fixed /sys/fs/cgroup namespace paths; null=max/unlimited or unavailable; v1 memory sentinel retained raw");
    }
    private static Map<String,Object> unavailableCgroup(String reason) {
        return object("version",null,"memory_max_bytes",null,"cpu_quota_us",null,"cpu_period_us",null,
                "cpuset_cpus_effective",null,"raw",Map.of(),"evidence",reason);
    }
    private static Long positive(String value) {
        try { long parsed=Long.parseLong(value); return parsed>0?parsed:null; }
        catch (NumberFormatException failure) { return null; }
    }
    private static String read(Path file) {
        // Kernel controls are tiny. Bound even unexpected mounted-file contents.
        try (var stream=Files.newInputStream(file)) {
            byte[] bytes=stream.readNBytes(4097); return bytes.length>4096?null:new String(bytes,StandardCharsets.US_ASCII).trim();
        } catch (IOException | SecurityException failure) { return null; }
    }
}
