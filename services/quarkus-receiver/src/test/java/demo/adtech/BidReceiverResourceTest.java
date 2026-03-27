package demo.adtech;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.kafka.InjectKafkaCompanion;
import io.quarkus.test.kafka.KafkaCompanionResource;
import io.smallrye.reactive.messaging.kafka.companion.KafkaCompanion;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.time.Duration;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.is;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the BidReceiverResource.
 * Uses Quarkus Dev Services to automatically spin up a Kafka container.
 */
@QuarkusTest
@QuarkusTestResource(KafkaCompanionResource.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BidReceiverResourceTest {

    @InjectKafkaCompanion
    KafkaCompanion companion;

    @BeforeAll
    void setup() {
        // Create the topic before tests run
        companion.topics().createAndWait("bids", 1);
    }

    @Test
    @DisplayName("Valid bid request should return 200 OK and be sent to Kafka")
    void testValidBidRequest() {
        String validBidJson = """
            {
                "id": "test-bid-123",
                "site": {
                    "id": "site-1",
                    "domain": "example.com"
                },
                "device": {
                    "ip": "192.168.1.1",
                    "ua": "Mozilla/5.0",
                    "os": "iOS",
                    "lmt": 0
                },
                "user": {
                    "id": "user-456"
                }
            }
            """;

        given()
                .contentType("application/json")
                .body(validBidJson)
                .when()
                .post("/bid-request")
                .then()
                .statusCode(200)
                .body("status", is("accepted"));

        // Verify message was sent to Kafka
        List<ConsumerRecord<String, String>> records = companion
                .consume(String.class, String.class)
                .fromTopics("bids", 1)
                .awaitCompletion(Duration.ofSeconds(10))
                .getRecords();

        assertEquals(1, records.size());
        assertTrue(records.get(0).value().contains("test-bid-123"));
    }

    @Test
    @DisplayName("Missing required fields should return 400 Bad Request")
    void testMissingRequiredFields() {
        // Missing 'id' field
        String invalidBidJson = """
            {
                "site": {
                    "domain": "example.com"
                },
                "device": {
                    "ip": "192.168.1.1"
                }
            }
            """;

        given()
                .contentType("application/json")
                .body(invalidBidJson)
                .when()
                .post("/bid-request")
                .then()
                .statusCode(400);
    }

    @Test
    @DisplayName("Missing both site and app should return 400 Bad Request")
    void testMissingSiteAndApp() {
        String invalidBidJson = """
            {
                "id": "test-bid-123",
                "device": {
                    "ip": "192.168.1.1"
                }
            }
            """;

        given()
                .contentType("application/json")
                .body(invalidBidJson)
                .when()
                .post("/bid-request")
                .then()
                .statusCode(400);
    }

    @Test
    @DisplayName("Missing device should return 400 Bad Request")
    void testMissingDevice() {
        String invalidBidJson = """
            {
                "id": "test-bid-123",
                "site": {
                    "domain": "example.com"
                }
            }
            """;

        given()
                .contentType("application/json")
                .body(invalidBidJson)
                .when()
                .post("/bid-request")
                .then()
                .statusCode(400);
    }

    @Test
    @DisplayName("LMT=1 (Limit Ad Tracking) should return 204 No Content")
    void testLimitAdTrackingEnabled() {
        String lmtBidJson = """
            {
                "id": "test-bid-lmt",
                "site": {
                    "domain": "example.com"
                },
                "device": {
                    "ip": "192.168.1.1",
                    "lmt": 1
                }
            }
            """;

        given()
                .contentType("application/json")
                .body(lmtBidJson)
                .when()
                .post("/bid-request")
                .then()
                .statusCode(204);
    }

    @Test
    @DisplayName("Blocked IP range (10.10.x.x) should return 204 No Content")
    void testBlockedIpRange() {
        String blockedIpBidJson = """
            {
                "id": "test-bid-blocked-ip",
                "site": {
                    "domain": "example.com"
                },
                "device": {
                    "ip": "10.10.5.100",
                    "lmt": 0
                }
            }
            """;

        given()
                .contentType("application/json")
                .body(blockedIpBidJson)
                .when()
                .post("/bid-request")
                .then()
                .statusCode(204);
    }

    @Test
    @DisplayName("App-based bid request (instead of site) should be accepted")
    void testAppBasedBidRequest() {
        String appBidJson = """
            {
                "id": "test-bid-app",
                "app": {
                    "bundle": "com.example.app"
                },
                "device": {
                    "ip": "192.168.1.1",
                    "lmt": 0
                }
            }
            """;

        given()
                .contentType("application/json")
                .body(appBidJson)
                .when()
                .post("/bid-request")
                .then()
                .statusCode(200)
                .body("status", is("accepted"));
    }

    @Test
    @DisplayName("Invalid JSON should return 400 Bad Request")
    void testInvalidJson() {
        String invalidJson = "{ this is not valid json }";

        given()
                .contentType("application/json")
                .body(invalidJson)
                .when()
                .post("/bid-request")
                .then()
                .statusCode(400);
    }
}


