package demo.adtech;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectWriter;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ApplicationTest {

    @Test
    void retainsHttpMapperDefaultsAlongsideKafkaWriter() {
        new ApplicationContextRunner()
                .withUserConfiguration(Application.class)
                .withPropertyValues("benchmark.delivery.mode=http-only")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    JsonMapper httpMapper = context.getBean(JsonMapper.class);
                    ObjectWriter kafkaWriter = context.getBean(ObjectWriter.class);
                    BidRequest request = new BidRequest();
                    request.id = "req-1";
                    request.site = new BidRequest.Site();
                    request.device = new BidRequest.Device();

                    assertEquals("{\"device\":{\"lmt\":0},\"id\":\"req-1\",\"site\":{}}",
                            httpMapper.writeValueAsString(request));
                    assertEquals("{\"id\":\"req-1\",\"site\":{},\"device\":{\"lmt\":0}}",
                            kafkaWriter.writeValueAsString(request));
                });
    }
}
