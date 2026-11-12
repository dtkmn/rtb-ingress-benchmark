package demo.adtech;

import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.json.JsonMapper;
import java.util.Map;

@RestController
public class ConformanceController {
    private final KafkaBidPublisher publisher;
    private final JsonMapper json;
    public ConformanceController(KafkaBidPublisher publisher) { this.publisher=publisher;this.json=publisher.conformance()==null?null:JsonMapper.builder().build(); }
    @GetMapping("/benchmark/conformance") public ResponseEntity<?> get() {
        return publisher.conformance()==null?ResponseEntity.notFound().build():ResponseEntity.ok(publisher.conformance().snapshot());
    }
    @PostMapping("/benchmark/conformance") public ResponseEntity<?> post(@RequestBody byte[] bytes) {
        try {
            @SuppressWarnings("unchecked") Map<String,Object> command=json.readValue(bytes,Map.class);
            return ResponseEntity.ok(publisher.conformance().command(command));
        } catch (tools.jackson.core.JacksonException failure) { return ResponseEntity.badRequest().body(Map.of("error","invalid conformance command JSON")); }
            catch (IllegalArgumentException failure) { return ResponseEntity.badRequest().body(Map.of("error",failure.getMessage())); }
        catch (IllegalStateException failure) { return ResponseEntity.status(409).body(Map.of("error",failure.getMessage())); }
        catch (Exception failure) { return ResponseEntity.status(500).body(Map.of("error","conformance operation failed")); }
    }
}
