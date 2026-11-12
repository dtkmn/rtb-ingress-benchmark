package demo.adtech;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import io.vertx.ext.web.Router;

@ApplicationScoped
public class PreparationFilter {
    public static final String LEASE = "benchmark.preparation";
    @Inject BidPublisher publisher;
    @Inject BenchmarkRuntime runtime;
    void configure(@Observes Router router) {
        router.route("/benchmark/conformance").order(Integer.MIN_VALUE).handler(context -> {
            ConformanceAdapter adapter=publisher.conformance();
            if (adapter==null || !adapter.authorized(context.request().headers().getAll("X-Conformance-Token"))
                    || (context.request().method()!=io.vertx.core.http.HttpMethod.GET && context.request().method()!=io.vertx.core.http.HttpMethod.POST)) {
                context.response().setStatusCode(404).end();return;
            }
            if (context.request().method()==io.vertx.core.http.HttpMethod.GET) context.next();
            else io.vertx.ext.web.handler.BodyHandler.create().setBodyLimit(8192).setHandleFileUploads(false).handle(context);
        }).blockingHandler(context -> {
            try {
                var adapter=publisher.conformance();
                java.util.Map<String,Object> result;
                if (context.request().method()==io.vertx.core.http.HttpMethod.GET) result=adapter.snapshot();
                else {
                    @SuppressWarnings("unchecked") java.util.Map<String,Object> command=io.vertx.core.json.Json.decodeValue(context.body().asString(),java.util.Map.class);
                    result=adapter.command(command);
                }
                context.response().putHeader("Content-Type","application/json").end(io.vertx.core.json.Json.encode(result));
            } catch (io.vertx.core.json.DecodeException failure) { context.response().setStatusCode(400).end(); }
            catch (IllegalArgumentException failure) { context.response().setStatusCode(400).end(); }
            catch (IllegalStateException failure) { context.response().setStatusCode(409).end(); }
            catch (Exception failure) { context.response().setStatusCode(500).end(); }
        },false);
        // Quarkus's body handler is MIN_VALUE + 1; reserve before it can buffer.
        router.post("/bid-request").order(Integer.MIN_VALUE).handler(context -> {
            OwnedPipeline pipeline = publisher.pipeline();
            if (!ReceiverContract.supportedHeaders(context.request().headers().getAll("Content-Type"), context.request().headers().getAll("Content-Encoding"))) {
                pipeline.httpStatus(415); context.response().setStatusCode(415).end(); return;
            }
            // REST's media parser is narrower about optional whitespace. Only normalize after raw validation.
            context.request().headers().set("Content-Type", "application/json");
            OwnedPipeline.Preparation lease = pipeline.prepare();
            if (lease == null) { pipeline.httpStatus(503); context.response().setStatusCode(503).end(); return; }
            context.put(LEASE, lease);
            context.addEndHandler(result -> { lease.close(); pipeline.httpStatus(context.response().getStatusCode()); });
            if (context.request().getHeader("Content-Length") != null) {
                try {
                    if (Long.parseLong(context.request().getHeader("Content-Length")) > 65536) {
                        context.response().setStatusCode(413).end(); return;
                    }
                } catch (NumberFormatException ignored) { /* transport owns malformed length */ }
            }
            io.vertx.ext.web.handler.BodyHandler.create().setBodyLimit(65536).setPreallocateBodyBuffer(false)
                    .setHandleFileUploads(false).handle(context);
        });
        router.get("/benchmark/runtime").handler(context -> context.response().putHeader("Content-Type", "application/json")
                .end(io.vertx.core.json.Json.encode(runtime.snapshot(context.vertx()))));
        router.get("/benchmark/state").handler(context -> context.response().putHeader("Content-Type", "application/json")
                .end(io.vertx.core.json.Json.encode(publisher.pipeline().state())));
    }
}
