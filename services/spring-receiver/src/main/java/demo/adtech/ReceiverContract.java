package demo.adtech;

import tools.jackson.core.json.JsonFactory;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

/** Raw receiver-v1 decoding; transport limits are enforced before this parser. */
public final class ReceiverContract {
    public static final int MAX_BODY_BYTES = 65536;
    private static final Pattern MEDIA = Pattern.compile("[\\t ]*application/json[\\t ]*(?:;[\\t ]*charset[\\t ]*=[\\t ]*(?:utf-8|\"utf-8\")[\\t ]*)?", Pattern.CASE_INSENSITIVE);
    private static final Pattern ENCODING = Pattern.compile("[\\t ]*identity[\\t ]*", Pattern.CASE_INSENSITIVE);
    private static final JsonFactory JSON = JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNumberLength(MAX_BODY_BYTES).maxStringLength(MAX_BODY_BYTES).build())
            .build();

    public static final class Invalid extends RuntimeException {
        public final int status;
        Invalid() { this(400); }
        Invalid(int status) { super("invalid receiver input"); this.status = status; }
    }
    public static boolean supportedHeaders(List<String> contentType, List<String> encoding) {
        return contentType != null && contentType.size() == 1 && contentType.get(0) != null
                && MEDIA.matcher(contentType.get(0)).matches()
                && (encoding == null || encoding.isEmpty() || (encoding.size() == 1 && encoding.get(0) != null && ENCODING.matcher(encoding.get(0)).matches()));
    }
    public static BidRequest parse(byte[] body) {
        if (body.length > MAX_BODY_BYTES) throw new Invalid(413);
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(body)).toString();
            if (!text.isEmpty() && text.charAt(0) == '\uFEFF') throw new Invalid();
            try (JsonParser parser = JSON.createParser(text)) {
                if (parser.nextToken() != JsonToken.START_OBJECT) throw new Invalid();
                BidRequest request = new BidRequest();
                for (String name; (name = property(parser)) != null;) {
                    switch (name) {
                        case "id" -> request.id = string(parser, false, 256);
                        case "site" -> {
                            Map<String,String> members = strings(parser, "id", "domain");
                            if (members != null) { request.site = new BidRequest.Site(); request.site.id = members.get("id"); request.site.domain = members.get("domain"); }
                        }
                        case "app" -> {
                            Map<String,String> members = strings(parser, "id", "bundle");
                            if (members != null) { request.app = new BidRequest.App(); request.app.id = members.get("id"); request.app.bundle = members.get("bundle"); }
                        }
                        case "device" -> request.device = device(parser);
                        case "user" -> {
                            Map<String,String> members = strings(parser, "id");
                            if (members != null) { request.user = new BidRequest.User(); request.user.id = members.get("id"); }
                        }
                        default -> throw new Invalid();
                    }
                }
                if (parser.nextToken() != null || request.id == null || asciiBlank(request.id)
                        || request.device == null || (request.site == null && request.app == null)) throw new Invalid();
                return request;
            }
        } catch (Invalid failure) { throw failure; }
        catch (Exception failure) { throw new Invalid(); }
    }
    private static String property(JsonParser parser) throws Exception {
        JsonToken token = parser.nextToken();
        if (token == JsonToken.END_OBJECT) return null;
        if (token != JsonToken.PROPERTY_NAME) throw new Invalid();
        String name = parser.currentName();
        scalars(name, MAX_BODY_BYTES);
        if (parser.nextToken() == null) throw new Invalid();
        return name;
    }
    private static Map<String,String> strings(JsonParser parser, String... allowed) throws Exception {
        if (parser.currentToken() == JsonToken.VALUE_NULL) return null;
        if (parser.currentToken() != JsonToken.START_OBJECT) throw new Invalid();
        Set<String> names = Set.of(allowed);
        Map<String,String> members = new HashMap<>();
        for (String name; (name = property(parser)) != null;) {
            if (!names.contains(name)) throw new Invalid();
            members.put(name, string(parser, true, 4096));
        }
        return members;
    }
    private static BidRequest.Device device(JsonParser parser) throws Exception {
        if (parser.currentToken() != JsonToken.START_OBJECT) throw new Invalid();
        BidRequest.Device device = new BidRequest.Device();
        for (String name; (name = property(parser)) != null;) {
            switch (name) {
                case "ip" -> device.ip = string(parser, true, 4096);
                case "os" -> device.os = string(parser, true, 4096);
                case "ua" -> device.ua = string(parser, true, 4096);
                case "lmt" -> {
                    if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT && parser.currentToken() != JsonToken.VALUE_NUMBER_FLOAT) throw new Invalid();
                    device.lmt = lmt(parser.getText());
                }
                default -> throw new Invalid();
            }
        }
        return device;
    }
    private static String string(JsonParser parser, boolean optional, int maximum) throws Exception {
        if (optional && parser.currentToken() == JsonToken.VALUE_NULL) return null;
        if (parser.currentToken() != JsonToken.VALUE_STRING) throw new Invalid();
        String value = parser.getText(); scalars(value, maximum); return value;
    }
    private static void scalars(String value, int maximum) {
        int count = 0;
        for (int index = 0; index < value.length(); index++) {
            char c = value.charAt(index);
            if (Character.isHighSurrogate(c)) {
                if (++index == value.length() || !Character.isLowSurrogate(value.charAt(index))) throw new Invalid();
            } else if (Character.isLowSurrogate(c)) throw new Invalid();
            if (++count > maximum) throw new Invalid();
        }
    }
    private static boolean asciiBlank(String value) {
        for (int index = 0; index < value.length(); index++) {
            char c = value.charAt(index);
            if (c != ' ' && (c < '\t' || c > '\r')) return false;
        }
        return true;
    }
    // Examine the original validated JSON token. No floating-point rounding or exponent-sized allocation.
    private static int lmt(String token) {
        int start = token.charAt(0) == '-' ? 1 : 0;
        int exponentAt = token.indexOf('e');
        if (exponentAt < 0) exponentAt = token.indexOf('E');
        int end = exponentAt < 0 ? token.length() : exponentAt;
        int point = token.indexOf('.');
        int fractional = point < 0 || point >= end ? 0 : end - point - 1;
        String digits = token.substring(start, end).replace(".", "");
        int first = 0, last = digits.length();
        while (first < last && digits.charAt(first) == '0') first++;
        if (first == last) return 0;
        while (last > first && digits.charAt(last - 1) == '0') last--;
        if (start != 0 || last - first != 1 || digits.charAt(first) != '1') throw new Invalid();
        int requiredExponent = fractional - (digits.length() - last);
        String exponent = exponentAt < 0 ? "0" : token.substring(exponentAt + 1);
        boolean negative = exponent.startsWith("-");
        int offset = negative || exponent.startsWith("+") ? 1 : 0;
        while (offset < exponent.length() - 1 && exponent.charAt(offset) == '0') offset++;
        String magnitude = exponent.substring(offset);
        String normalized = (negative && !magnitude.equals("0") ? "-" : "") + magnitude;
        if (!normalized.equals(Integer.toString(requiredExponent))) throw new Invalid();
        return 1;
    }
    private ReceiverContract() {}
}
