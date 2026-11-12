package demo.adtech;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;

@RestController
public class BenchmarkStateController {
    private final KafkaBidPublisher publisher;
    public BenchmarkStateController(KafkaBidPublisher publisher) { this.publisher = publisher; }
    @GetMapping("/benchmark/state") public Map<String, Object> state() { return publisher.pipeline().state(); }
}
