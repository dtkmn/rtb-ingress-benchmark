package demo.adtech;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

final class ContractFixtures {
    static final ObjectMapper JSON = new ObjectMapper();
    static List<JsonNode> cases() throws Exception {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null && !Files.exists(directory.resolve("tests/conformance/corpus.json"))) directory = directory.getParent();
        if (directory == null) throw new IllegalStateException("shared corpus not found");
        List<JsonNode> cases = new ArrayList<>();
        for (JsonNode fixture : JSON.readTree(directory.resolve("tests/conformance/corpus.json").toFile()).get("cases"))
            if (!fixture.get("category").asText().equals("fault")) cases.add(fixture);
        if (cases.size() != 52) throw new IllegalStateException("expected52 nonfault fixtures");
        return cases;
    }
    static byte[] body(JsonNode fixture) {
        JsonNode body = fixture.get("request").get("body");
        return body.get("encoding").asText().equals("base64") ? Base64.getDecoder().decode(body.get("data").asText())
                : body.get("data").asText().getBytes(StandardCharsets.UTF_8);
    }
    static List<String> headers(JsonNode fixture, String name) {
        List<String> values = new ArrayList<>();
        for (JsonNode header : fixture.get("request").get("headers")) if (header.get("name").asText().equalsIgnoreCase(name)) values.add(header.get("value").asText());
        return values;
    }
    static int status(JsonNode fixture) { return fixture.get("expectations").get("http-only").get("status").asInt(); }
    private ContractFixtures() {}
}
