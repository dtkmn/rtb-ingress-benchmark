package demo.adtech;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for BidRecord entity.
 */
class BidRecordTest {

    @Test
    @DisplayName("BidRecord should correctly map from BidRequest with site")
    void testBidRecordFromSiteRequest() {
        // Arrange
        BidRequest request = new BidRequest();
        request.id = "test-123";
        request.site = new BidRequest.Site();
        request.site.domain = "example.com";
        request.device = new BidRequest.Device();
        request.device.ip = "192.168.1.1";
        request.device.os = "iOS";
        request.device.lmt = 0;

        // Act
        BidRecord record = new BidRecord(request);

        // Assert
        assertEquals("test-123", record.bidRequestId);
        assertEquals("example.com", record.domain);
        assertNull(record.appBundle);
        assertEquals("192.168.1.1", record.ip);
        assertEquals("iOS", record.os);
        assertFalse(record.limitAdTracking);
        assertNotNull(record.processedAt);
        assertTrue(record.processedAt.isBefore(Instant.now().plusSeconds(1)));
    }

    @Test
    @DisplayName("BidRecord should correctly map from BidRequest with app")
    void testBidRecordFromAppRequest() {
        // Arrange
        BidRequest request = new BidRequest();
        request.id = "test-456";
        request.app = new BidRequest.App();
        request.app.bundle = "com.example.app";
        request.device = new BidRequest.Device();
        request.device.ip = "10.0.0.1";
        request.device.os = "Android";
        request.device.lmt = 1;

        // Act
        BidRecord record = new BidRecord(request);

        // Assert
        assertEquals("test-456", record.bidRequestId);
        assertNull(record.domain);
        assertEquals("com.example.app", record.appBundle);
        assertEquals("10.0.0.1", record.ip);
        assertEquals("Android", record.os);
        assertTrue(record.limitAdTracking);
    }

    @Test
    @DisplayName("BidRecord should handle null site and app gracefully")
    void testBidRecordWithNullSiteAndApp() {
        // Arrange
        BidRequest request = new BidRequest();
        request.id = "test-789";
        request.device = new BidRequest.Device();
        request.device.ip = "172.16.0.1";

        // Act
        BidRecord record = new BidRecord(request);

        // Assert
        assertEquals("test-789", record.bidRequestId);
        assertNull(record.domain);
        assertNull(record.appBundle);
    }

    @Test
    @DisplayName("BidRecord should handle null device gracefully")
    void testBidRecordWithNullDevice() {
        // Arrange
        BidRequest request = new BidRequest();
        request.id = "test-null-device";
        request.site = new BidRequest.Site();
        request.site.domain = "test.com";

        // Act
        BidRecord record = new BidRecord(request);

        // Assert
        assertEquals("test-null-device", record.bidRequestId);
        assertEquals("test.com", record.domain);
        assertNull(record.ip);
        assertNull(record.os);
        assertFalse(record.limitAdTracking);
    }

    @Test
    @DisplayName("Default constructor should create empty BidRecord")
    void testDefaultConstructor() {
        // Act
        BidRecord record = new BidRecord();

        // Assert
        assertNull(record.bidRequestId);
        assertNull(record.domain);
        assertNull(record.processedAt);
    }
}


