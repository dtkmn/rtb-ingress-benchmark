package demo.adtech;

import io.netty.util.concurrent.EventExecutorGroup;
import org.springframework.boot.reactor.netty.NettyServerCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

@RestController
public class BenchmarkRuntime {
    private final KafkaBidPublisher publisher;
    private final BenchmarkSettings settings;
    public BenchmarkRuntime(KafkaBidPublisher publisher,BenchmarkSettings settings) {
        RuntimeEvidence.requireControlled(System.getenv("BENCHMARK_TELEMETRY_PROFILE"));
        this.publisher=publisher; this.settings=settings;
    }
    @Bean NettyServerCustomizer controlledNetty() {
        return server -> server.accessLog(false).errorLog(false).wiretap(false).metrics(false,uri -> uri);
    }
    @GetMapping("/benchmark/runtime") public Map<String,Object> runtime(ServerHttpRequest request) {
        AtomicReference<List<String>> handlers=new AtomicReference<>(List.of());
        AtomicReference<Integer> loops=new AtomicReference<>();
        Object nativeRequest=ServerHttpRequestDecorator.getNativeRequest(request);
        if (nativeRequest instanceof reactor.netty.http.server.HttpServerRequest reactorRequest) reactorRequest.withConnection(connection -> {
            handlers.set(connection.channel().pipeline().names());
            EventExecutorGroup group=connection.channel().eventLoop().parent(); int count=0; for (var ignored:group) count++;
            loops.set(count);
        });
        boolean optionalHandler=handlers.get().stream().anyMatch(name -> {
            String n=name.toLowerCase(java.util.Locale.ROOT); return n.contains("accesslog") || n.contains("metrics") || n.contains("tracing") || n.contains("wirelogging");
        });
        if (optionalHandler) throw new IllegalStateException("controlled HTTP channel contains optional telemetry handler");
        Map<String,Object> parallelism=RuntimeEvidence.object("control","HTTP_SERVER_WORKERS",
                "requested",RuntimeEvidence.requestedInt("HTTP_SERVER_WORKERS",reactor.netty.resources.LoopResources.DEFAULT_IO_WORKER_COUNT),"effective",loops.get(),
                "evidence",loops.get()==null?"actual Reactor Netty request channel unavailable":
                "reactor.netty.ioWorkerCount system property="+System.getProperty("reactor.netty.ioWorkerCount")+"; public current HTTP request channel eventLoop.parent iterator count; HTTP loops, not total process threads");
        var telemetry=RuntimeEvidence.controlledTelemetry("actual current HTTP channel pipeline contains no access-log, metrics, tracing or wire-log handlers; applied Netty accessLog/errorLog/wiretap/metrics(false); no application request instrumentation");
        telemetry.put("http_channel_handlers",handlers.get());
        return RuntimeEvidence.snapshot("spring-webflux",settings.deliveryMode(),telemetry,parallelism,
                publisher.producerRuntime(),publisher.pipeline(),false);
    }
}
