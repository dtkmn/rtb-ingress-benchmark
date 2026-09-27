package demo.adtech;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

// This is the Panache Entity that will be saved to Postgres.
@Entity
@Table(name = "bid_records")
public class BidRecord extends PanacheEntityBase {

    // Match the BIGSERIAL identity supplied by the database initialization SQL.
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "bid_request_id", nullable = false)
    public String bidRequestId; // The 'id' from the BidRequest
    public String domain;
    @Column(name = "app_bundle")
    public String appBundle;
    @Column(length = 45)
    public String ip;
    @Column(length = 50)
    public String os;
    @Column(name = "limit_ad_tracking")
    public boolean limitAdTracking;
    @Column(name = "processed_at")
    public Instant processedAt;

    // Default constructor required for Hibernate
    public BidRecord() {}

    /**
     * Helper constructor to map from the Kafka object to the DB object.
     */
    public BidRecord(BidRequest request) {
        this.bidRequestId = request.id;

        if (request.site != null) {
            this.domain = request.site.domain;
        }
        if (request.app != null) {
            this.appBundle = request.app.bundle;
        }
        if (request.device != null) {
            this.ip = request.device.ip;
            this.os = request.device.os;
            this.limitAdTracking = (request.device.lmt == 1);
        }

        this.processedAt = Instant.now();
    }
}
