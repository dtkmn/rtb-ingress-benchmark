package demo.adtech;

import io.quarkus.runtime.Startup;
import io.quarkus.runtime.ImageMode;
import io.quarkus.opentelemetry.runtime.config.build.OTelBuildConfig;
import io.quarkus.micrometer.runtime.config.MicrometerConfig;
import io.quarkus.vertx.http.runtime.VertxHttpConfig;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.Map;

@Startup
@ApplicationScoped
public class BenchmarkRuntime {
    @Inject OTelBuildConfig otel;
    @Inject MicrometerConfig micrometer;
    @Inject VertxHttpConfig http;
    @Inject Instance<io.opentelemetry.api.OpenTelemetry> telemetry;
    @Inject Instance<io.micrometer.core.instrument.MeterRegistry> registries;
    @Inject BidPublisher publisher;
    @Inject BenchmarkSettings settings;
    @PostConstruct void validate() {
        RuntimeEvidence.requireControlled(System.getenv("BENCHMARK_TELEMETRY_PROFILE"));
        if (otel.enabled() || micrometer.enabled() || http.accessLog().enabled()) throw new IllegalStateException(
                "controlled benchmark requires build-time OTel/Micrometer disabled and HTTP access log disabled");
    }
    public Map<String,Object> snapshot(io.vertx.core.Vertx vertx) {
        int loops=0; for (var ignored:vertx.nettyEventLoopGroup()) loops++;
        boolean measured=vertx.isMetricsEnabled();
        if (measured || !telemetry.isUnsatisfied() || !registries.isUnsatisfied()) throw new IllegalStateException(
                "controlled telemetry runtime proof failed: unexpected Vert.x metrics or OTel/MeterRegistry bean");
        Map<String,Object> parallelism=RuntimeEvidence.object("control","QUARKUS_HTTP_IO_THREADS",
                "requested",RuntimeEvidence.requestedInt("QUARKUS_HTTP_IO_THREADS",2),"effective",loops,
                "evidence","QUARKUS_HTTP_IO_THREADS mapped to quarkus.vertx.event-loops-pool-size; public running Vertx.nettyEventLoopGroup iterator count; HTTP event loops, not total process threads");
        return RuntimeEvidence.snapshot(ImageMode.current().isNativeImage()?"quarkus-native":"quarkus-jvm",settings.deliveryMode(),
                RuntimeEvidence.controlledTelemetry("running fixed OTelBuildConfig.enabled=false, MicrometerConfig.enabled=false; VertxHttpConfig accessLog=false; no CDI OpenTelemetry/MeterRegistry; public Vertx.isMetricsEnabled=false"),
                parallelism,publisher.producerRuntime(),publisher.pipeline(),ImageMode.current().isNativeImage());
    }
}
