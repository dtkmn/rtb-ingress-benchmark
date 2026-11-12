package demo.adtech;
import org.junit.jupiter.api.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

class ReceiverContractTest {
    @TestFactory Stream<DynamicTest> sharedNonfaultCorpusAndCanonicalValues() throws Exception {
        return ContractFixtures.cases().stream().map(fixture -> DynamicTest.dynamicTest(fixture.get("id").asText(), () -> {
            int status;
            BidRequest request = null;
            if (!ReceiverContract.supportedHeaders(ContractFixtures.headers(fixture,"Content-Type"), ContractFixtures.headers(fixture,"Content-Encoding"))) status = 415;
            else {
                try {
                    request = ReceiverContract.parse(ContractFixtures.body(fixture));
                    status = request.device.lmt == 1 || (request.device.ip != null && request.device.ip.startsWith("10.10.")) ? 204 : 200;
                } catch (ReceiverContract.Invalid invalid) { status = invalid.status; }
            }
            assertEquals(ContractFixtures.status(fixture), status);
            if (status == 200) {
                var record = fixture.get("expectations").get("confirm").get("record");
                assertEquals(record.get("key").asText(), request.id);
                assertEquals(record.get("value"), ContractFixtures.JSON.readTree(ContractFixtures.JSON.writer().writeValueAsBytes(request)));
            }
        }));
    }
    @Test void exactNumericTokensAgainstBoundedDecimalOracle() {
        Random random = new Random(713);
        List<String> tokens = new ArrayList<>(List.of("0", "-0", "1", "1.0", "1e0", "10e-1", "0.100e+1", "100.00E-2", "1.0000000000000000001", "0.9999999999999999999"));
        for (int index=0;index<2000;index++) tokens.add((random.nextBoolean()?"-":"")+random.nextInt(20)+"."+random.nextInt(10000)+"e"+(random.nextInt(61)-30));
        for (String token : tokens) {
            BigDecimal exact = new BigDecimal(token);
            if (exact.compareTo(BigDecimal.ZERO)==0 || exact.compareTo(BigDecimal.ONE)==0)
                assertEquals(exact.intValueExact(), requestWithLmt(token).device.lmt, token);
            else assertThrows(ReceiverContract.Invalid.class, () -> requestWithLmt(token), token);
        }
    }
    @Test void zeroHugeExponentAndLongEquivalentOneDoNotRoundOrOverflow() {
        assertEquals(0, requestWithLmt("-0.000e9999999999999999999999999999").device.lmt);
        assertEquals(1, requestWithLmt("1"+"0".repeat(2000)+"e-2000").device.lmt);
        assertThrows(ReceiverContract.Invalid.class, () -> requestWithLmt("1e9999999999999999999999999999"));
        assertThrows(ReceiverContract.Invalid.class, () -> requestWithLmt("1.000000000000000000000000000000001"));
    }
    @Test void scalarLengthsAndAsciiOnlyBlankRulePreserveOriginalId() throws Exception {
        for (String id : List.of(" \t keep \r", "\u00a0", "\u2003", "😀".repeat(256)))
            assertEquals(id, parse("{\"id\":"+ContractFixtures.JSON.writeValueAsString(id)+",\"site\":{},\"device\":{}}").id);
        assertThrows(ReceiverContract.Invalid.class, () -> parse("{\"id\":\""+"😀".repeat(257)+"\",\"site\":{},\"device\":{}}"));
        assertThrows(ReceiverContract.Invalid.class, () -> parse("{\"id\":\"\\t\\n\\r \",\"site\":{},\"device\":{}}"));
    }
    @Test void decodedNamesAndLoneSurrogatesAreRejectedAtEverySupportedLevel() {
        for (String object : List.of("{\"id\":\"a\",\"\\u0069d\":\"b\",\"site\":{},\"device\":{}}",
                "{\"id\":\"a\",\"site\":{\"id\":\"a\",\"\\u0069d\":\"b\"},\"device\":{}}",
                "{\"id\":\"a\",\"site\":{},\"device\":{\"ua\":\"\\uD800\"}}",
                "{\"id\":\"a\",\"site\":{},\"device\":{},\"user\":{\"id\":\"\\uDC00\"}}"))
            assertThrows(ReceiverContract.Invalid.class, () -> parse(object));
        assertEquals("\uD800\uDC00", parse("{\"id\":\"\\uD800\\uDC00\",\"site\":{},\"device\":{}}").id);
    }
    @Test void unsupportedMediaWinsOverOversizedOrInvalidBody() {
        assertFalse(ReceiverContract.supportedHeaders(List.of("application/json; foo=bar"),List.of()));
        assertFalse(ReceiverContract.supportedHeaders(List.of("application/json","application/json"),List.of()));
        assertFalse(ReceiverContract.supportedHeaders(List.of("application/json"),List.of("identity","identity")));
        assertTrue(ReceiverContract.supportedHeaders(List.of(" \tApplication/JSON ; Charset = \"UTF-8\"\t"),List.of(" IDENTITY ")));
    }
    private static BidRequest requestWithLmt(String token) { return parse("{\"id\":\"n\",\"site\":{},\"device\":{\"lmt\":"+token+"}}"); }
    private static BidRequest parse(String json) { return ReceiverContract.parse(json.getBytes(StandardCharsets.UTF_8)); }
}
