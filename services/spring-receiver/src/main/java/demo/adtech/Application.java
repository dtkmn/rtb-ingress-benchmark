package demo.adtech;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.ObjectWriter;
import tools.jackson.databind.json.JsonMapper;

@SpringBootApplication
public class Application {

    public static void main(String[] args) {
        String workers=System.getenv("HTTP_SERVER_WORKERS");
        if (workers!=null) {
            int count=Integer.parseInt(workers.trim());
            if (count<1) throw new IllegalArgumentException("HTTP_SERVER_WORKERS must be positive");
            System.setProperty("reactor.netty.ioWorkerCount",Integer.toString(count));
        }
        SpringApplication.run(Application.class, args);
    }

    @Bean
    ObjectWriter bidPayloadWriter() {
        // Preserve Kafka payload defaults and field order while retaining Boot's HTTP JsonMapper.
        return JsonMapper.builderWithJackson2Defaults().build().writer();
    }
}
