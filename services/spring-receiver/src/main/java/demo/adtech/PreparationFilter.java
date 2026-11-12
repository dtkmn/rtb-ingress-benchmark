package demo.adtech;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PreparationFilter implements WebFilter {
    public static final String LEASE = "benchmark.preparation";
    private final KafkaBidPublisher publisher;
    public PreparationFilter(KafkaBidPublisher publisher) { this.publisher = publisher; }
    @Override public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (exchange.getRequest().getPath().value().equals("/benchmark/conformance")) {
            ConformanceAdapter adapter=publisher.conformance();
            var method=exchange.getRequest().getMethod();
            if (adapter==null || !adapter.authorized(exchange.getRequest().getHeaders().get("X-Conformance-Token"))
                    || (method!=org.springframework.http.HttpMethod.GET && method!=org.springframework.http.HttpMethod.POST)) {
                exchange.getResponse().setStatusCode(HttpStatus.NOT_FOUND);return exchange.getResponse().setComplete();
            }
            if (method==org.springframework.http.HttpMethod.GET) return chain.filter(exchange);
            return DataBufferUtils.join(exchange.getRequest().getBody(),8192).map(buffer -> {
                byte[] body=new byte[buffer.readableByteCount()];buffer.read(body);DataBufferUtils.release(buffer);return body;
            }).defaultIfEmpty(new byte[0]).flatMap(body -> chain.filter(exchange.mutate().request(new ServerHttpRequestDecorator(exchange.getRequest()) {
                @Override public Flux<org.springframework.core.io.buffer.DataBuffer> getBody() { return Flux.defer(() -> Flux.just(exchange.getResponse().bufferFactory().wrap(body))); }
            }).build())).onErrorResume(DataBufferLimitException.class,failure -> {
                exchange.getResponse().setStatusCode(HttpStatus.CONTENT_TOO_LARGE);return exchange.getResponse().setComplete();
            });
        }
        if (!exchange.getRequest().getPath().value().equals("/bid-request") || !exchange.getRequest().getMethod().name().equals("POST")) return chain.filter(exchange);
        OwnedPipeline pipeline = publisher.pipeline();
        exchange.getResponse().beforeCommit(() -> {
            org.springframework.http.HttpStatusCode status = exchange.getResponse().getStatusCode();
            pipeline.httpStatus(status == null ? 200 : status.value());
            return Mono.empty();
        });
        if (!ReceiverContract.supportedHeaders(exchange.getRequest().getHeaders().get("Content-Type"), exchange.getRequest().getHeaders().get("Content-Encoding"))) {
            exchange.getResponse().setStatusCode(HttpStatus.UNSUPPORTED_MEDIA_TYPE); return exchange.getResponse().setComplete();
        }
        OwnedPipeline.Preparation lease = pipeline.prepare();
        if (lease == null) { exchange.getResponse().setStatusCode(HttpStatus.SERVICE_UNAVAILABLE); return exchange.getResponse().setComplete(); }
        exchange.getAttributes().put(LEASE, lease);
        if (exchange.getRequest().getHeaders().getContentLength() > 65536) {
            lease.close(); exchange.getResponse().setStatusCode(HttpStatus.CONTENT_TOO_LARGE); return exchange.getResponse().setComplete();
        }
        // join releases constituent buffers on overflow/cancellation; the retained copy is <=64 KiB.
        return DataBufferUtils.join(exchange.getRequest().getBody(), 65536)
                .map(buffer -> { byte[] body = new byte[buffer.readableByteCount()]; buffer.read(body); DataBufferUtils.release(buffer); return body; })
                .defaultIfEmpty(new byte[0])
                .flatMap(body -> chain.filter(exchange.mutate().request(new ServerHttpRequestDecorator(exchange.getRequest()) {
                    @Override public Flux<org.springframework.core.io.buffer.DataBuffer> getBody() {
                        return Flux.defer(() -> Flux.just(exchange.getResponse().bufferFactory().wrap(body)));
                    }
                }).build()))
                .onErrorResume(DataBufferLimitException.class, failure -> {
                    exchange.getResponse().setStatusCode(HttpStatus.CONTENT_TOO_LARGE); return exchange.getResponse().setComplete();
                })
                .doFinally(signal -> lease.close());
    }
}
