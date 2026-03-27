package demo.adtech;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for DeadLetterQueueService.FailedBidRecord.
 */
class DeadLetterQueueServiceTest {

    @Test
    @DisplayName("FailedBidRecord should correctly capture failure information")
    void testFailedBidRecordCreation() {
        // Arrange
        BidRequest request = new BidRequest();
        request.id = "failed-bid-123";
        request.site = new BidRequest.Site();
        request.site.domain = "example.com";

        Exception error = new RuntimeException("Database connection failed");
        String operation = "DATABASE_PERSIST";
        Instant failedAt = Instant.now();

        // Act
        DeadLetterQueueService.FailedBidRecord failedRecord = new DeadLetterQueueService.FailedBidRecord(
                request,
                error.getClass().getName(),
                error.getMessage(),
                operation,
                failedAt
        );

        // Assert
        assertNotNull(failedRecord.originalRequest());
        assertEquals("failed-bid-123", failedRecord.originalRequest().id);
        assertEquals("java.lang.RuntimeException", failedRecord.errorType());
        assertEquals("Database connection failed", failedRecord.errorMessage());
        assertEquals("DATABASE_PERSIST", failedRecord.failedOperation());
        assertEquals(failedAt, failedRecord.failedAt());
    }

    @Test
    @DisplayName("FailedBidRecord should handle null error message")
    void testFailedBidRecordWithNullMessage() {
        // Arrange
        BidRequest request = new BidRequest();
        request.id = "failed-bid-456";

        Exception error = new NullPointerException();
        Instant failedAt = Instant.now();

        // Act
        DeadLetterQueueService.FailedBidRecord failedRecord = new DeadLetterQueueService.FailedBidRecord(
                request,
                error.getClass().getName(),
                error.getMessage(), // null
                "VALIDATION",
                failedAt
        );

        // Assert
        assertEquals("java.lang.NullPointerException", failedRecord.errorType());
        assertNull(failedRecord.errorMessage());
    }
}


