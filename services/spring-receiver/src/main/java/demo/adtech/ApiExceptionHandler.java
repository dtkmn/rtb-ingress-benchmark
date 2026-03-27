package demo.adtech;

import org.springframework.core.codec.DecodingException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ServerWebInputException;
import reactor.core.publisher.Mono;

import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler({ServerWebInputException.class, DecodingException.class})
    public Mono<ResponseEntity<Map<String, String>>> handleHttpMessageNotReadable() {
        return Mono.just(ResponseEntity.badRequest().body(Map.of("status", "bad request")));
    }
}
