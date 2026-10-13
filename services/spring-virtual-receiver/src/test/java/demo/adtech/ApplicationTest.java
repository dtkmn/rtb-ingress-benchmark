package demo.adtech;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectWriter;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class ApplicationTest {

    @Test
    void retainsBootHttpMapperAlongsideKafkaPayloadWriter() {
        new ApplicationContextRunner()
                .withUserConfiguration(Application.class)
                .withPropertyValues("benchmark.delivery.mode=http-only")
                .run(context -> {
                    assertThat(context).hasNotFailed()
                            .hasSingleBean(JsonMapper.class)
                            .hasSingleBean(ObjectWriter.class)
                            .hasBean("bidPayloadWriter");

                    BidRequest request = new BidRequest();
                    request.id = "req-1";
                    request.site = new BidRequest.Site();
                    request.device = new BidRequest.Device();

                    assertThat(context.getBean(JsonMapper.class).writeValueAsString(request))
                            .isEqualTo("{\"device\":{\"lmt\":0},\"id\":\"req-1\",\"site\":{}}");
                    assertThat(context.getBean("bidPayloadWriter", ObjectWriter.class).writeValueAsString(request))
                            .isEqualTo("{\"id\":\"req-1\",\"site\":{},\"device\":{\"lmt\":0}}");
                });
    }
}
