package demo.adtech;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.ObjectWriter;
import tools.jackson.databind.json.JsonMapper;

@SpringBootApplication
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }

    @Bean
    ObjectWriter bidPayloadWriter() {
        // Preserve Kafka payload defaults and field order while retaining Boot's HTTP JsonMapper.
        return JsonMapper.builderWithJackson2Defaults().build().writer();
    }
}
