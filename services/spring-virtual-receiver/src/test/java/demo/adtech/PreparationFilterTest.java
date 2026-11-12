package demo.adtech;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.MediaType;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.util.ArrayList;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.junit.jupiter.api.Assertions.*;

class PreparationFilterTest {
    @Test void gatesBeforeBindingAndBoundsUnknownLengthConverterReads() throws Exception {
        BenchmarkSettings settings = BenchmarkSettings.forTests("http-only", "localhost:9092", "bids", "1");
        KafkaBidPublisher publisher = new KafkaBidPublisher(settings);
        jakarta.servlet.Filter unknownLength = (request,response,chain) -> chain.doFilter(new HttpServletRequestWrapper((jakarta.servlet.http.HttpServletRequest) request) {
            @Override public long getContentLengthLong() { return -1; }
            @Override public int getContentLength() { return -1; }
        }, response);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new BidReceiverController(publisher, settings, new Application().bidPayloadWriter()))
                .setControllerAdvice(new ApiExceptionHandler()).addFilters(unknownLength, new PreparationFilter(publisher)).build();
        ArrayList<OwnedPipeline.Preparation> occupied = new ArrayList<>();
        try {
            for (int index=0; index<128; index++) occupied.add(publisher.pipeline().prepare());
            mvc.perform(post("/bid-request").contentType(MediaType.TEXT_PLAIN).content("{")).andExpect(status().is(415));
            assertEquals(0L, publisher.pipeline().state().get("preparation_rejected"));
            mvc.perform(post("/bid-request").contentType(MediaType.APPLICATION_JSON).content("{")).andExpect(status().isServiceUnavailable());
            occupied.forEach(OwnedPipeline.Preparation::close);
            String json="{\"id\":\"k\",\"site\":{},\"device\":{}}";
            for (int length : new int[]{65536,65537}) mvc.perform(post("/bid-request").contentType(MediaType.APPLICATION_JSON)
                    .content(json + " ".repeat(length-json.length()))).andExpect(status().is(length==65536?200:413));
            assertEquals(0, publisher.pipeline().state().get("preparation_count"));
            assertEquals(1L, publisher.pipeline().state().get("preparation_rejected"));
        } finally { occupied.forEach(OwnedPipeline.Preparation::close); publisher.close(); }
    }
}
