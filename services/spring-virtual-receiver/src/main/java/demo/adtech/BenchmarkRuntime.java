package demo.adtech;

import org.apache.catalina.Container;
import org.apache.catalina.valves.AccessLogValve;
import org.springframework.boot.tomcat.TomcatWebServer;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;

@RestController
public class BenchmarkRuntime {
    private final KafkaBidPublisher publisher;
    private final BenchmarkSettings settings;
    private final org.springframework.context.ApplicationContext context;
    public BenchmarkRuntime(KafkaBidPublisher publisher,BenchmarkSettings settings,org.springframework.context.ApplicationContext context,Environment environment) {
        RuntimeEvidence.requireControlled(System.getenv("BENCHMARK_TELEMETRY_PROFILE"));
        if (environment.getProperty("server.tomcat.accesslog.enabled",Boolean.class,false)) throw new IllegalArgumentException("controlled benchmark requires Tomcat access log disabled");
        this.publisher=publisher; this.settings=settings; this.context=context;
    }
    @GetMapping("/benchmark/runtime") public Map<String,Object> runtime() {
        Object executor=null; boolean accessLog=false;
        if (context instanceof WebServerApplicationContext webContext && webContext.getWebServer() instanceof TomcatWebServer server) {
            var handler=server.getTomcat().getConnector().getProtocolHandler();
            executor=handler.getExecutor()==null?null:handler.getExecutor().getClass().getName();
            accessLog=hasAccessLog(server.getTomcat().getEngine());
        }
        if (executor==null) throw new IllegalStateException("actual running Tomcat executor unavailable");
        if (accessLog) throw new IllegalStateException("controlled Tomcat runtime has enabled AccessLogValve");
        var parallelism=RuntimeEvidence.object("control","spring.threads.virtual.enabled","requested",true,"effective",null,
                "evidence","virtual request executor has no fixed HTTP worker-count equivalent; actual executor and current request thread reported",
                "executor_class",executor,"current_request_thread_virtual",Thread.currentThread().isVirtual());
        return RuntimeEvidence.snapshot("spring-virtual",settings.deliveryMode(),
                RuntimeEvidence.controlledTelemetry("running Tomcat container/host/context pipelines have no enabled AccessLogValve; no application request instrumentation or tracing/metrics dependencies"),
                parallelism,publisher.producerRuntime(),publisher.pipeline(),false);
    }
    private static boolean hasAccessLog(Container container) {
        for (var valve:container.getPipeline().getValves()) if (valve instanceof AccessLogValve log && log.getEnabled()) return true;
        for (Container child:container.findChildren()) if (hasAccessLog(child)) return true;
        return false;
    }
}
