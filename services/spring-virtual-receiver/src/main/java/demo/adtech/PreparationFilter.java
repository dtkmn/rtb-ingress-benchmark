package demo.adtech;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PreparationFilter extends OncePerRequestFilter {
    public static final String LEASE = "benchmark.preparation";
    private final KafkaBidPublisher publisher;
    public PreparationFilter(KafkaBidPublisher publisher) { this.publisher = publisher; }
    static final class TooLarge extends IOException {}
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        if (request.getRequestURI().equals("/benchmark/conformance")) {
            ConformanceAdapter adapter=publisher.conformance();
            if (adapter==null || !adapter.authorized(java.util.Collections.list(request.getHeaders("X-Conformance-Token")))
                    || (!request.getMethod().equals("GET") && !request.getMethod().equals("POST"))) { response.setStatus(404);return; }
            if (request.getMethod().equals("GET")) { chain.doFilter(request,response);return; }
            if (request.getContentLengthLong()>8192) { response.setStatus(413);return; }
            byte[] body=request.getInputStream().readNBytes(8193);if (body.length>8192) { response.setStatus(413);return; }
            chain.doFilter(new HttpServletRequestWrapper(request) {
                @Override public ServletInputStream getInputStream() {
                    var bytes=new java.io.ByteArrayInputStream(body);
                    return new ServletInputStream() {
                        @Override public int read() { return bytes.read(); }
                        @Override public int read(byte[] target,int offset,int count) { return bytes.read(target,offset,count); }
                        @Override public boolean isFinished() { return bytes.available()==0; }
                        @Override public boolean isReady() { return true; }
                        @Override public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException("synchronous bounded conformance body"); }
                    };
                }
            },response);return;
        }
        if (!request.getRequestURI().equals("/bid-request") || !request.getMethod().equals("POST")) { chain.doFilter(request, response); return; }
        OwnedPipeline pipeline = publisher.pipeline();
        if (!ReceiverContract.supportedHeaders(java.util.Collections.list(request.getHeaders("Content-Type")), java.util.Collections.list(request.getHeaders("Content-Encoding")))) {
            pipeline.httpStatus(415); response.setStatus(415); return;
        }
        OwnedPipeline.Preparation lease = pipeline.prepare();
        if (lease == null) { pipeline.httpStatus(503); response.setStatus(503); return; }
        request.setAttribute(LEASE, lease);
        try {
            if (request.getContentLengthLong() > 65536) { response.setStatus(413); return; }
            // Count the actual stream even when Content-Length is absent or chunked.
            HttpServletRequest wrapped = new HttpServletRequestWrapper(request) {
                private ServletInputStream bounded;
                @Override public ServletInputStream getInputStream() throws IOException {
                    if (bounded != null) return bounded;
                    ServletInputStream source = super.getInputStream();
                    bounded = new ServletInputStream() {
                        long bytes;
                        private int count(int amount) throws IOException { if (amount > 0 && (bytes += amount) > 65536) throw new TooLarge(); return amount; }
                        @Override public int read() throws IOException { int value = source.read(); if (value >= 0) count(1); return value; }
                        @Override public int read(byte[] data, int offset, int length) throws IOException { return count(source.read(data, offset, Math.min(length, (int) Math.max(1, 65537 - bytes)))); }
                        @Override public boolean isFinished() { return source.isFinished(); }
                        @Override public boolean isReady() { return source.isReady(); }
                        @Override public void setReadListener(ReadListener listener) { source.setReadListener(listener); }
                    };
                    return bounded;
                }
            };
            chain.doFilter(wrapped, response);
        } catch (Exception failure) {
            Throwable cause = failure;
            while (cause != null && !(cause instanceof TooLarge)) cause = cause.getCause();
            if (cause != null) response.setStatus(413);
            else if (failure instanceof ServletException servlet) throw servlet;
            else if (failure instanceof IOException io) throw io;
            else throw new ServletException(failure);
        } finally { lease.close(); pipeline.httpStatus(response.getStatus()); }
    }
}
