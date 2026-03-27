-- V1__Create_bid_records_table.sql
-- Initial schema for bid records

CREATE TABLE IF NOT EXISTS bid_records (
    id BIGSERIAL PRIMARY KEY,
    bid_request_id VARCHAR(255) NOT NULL,
    domain VARCHAR(255),
    app_bundle VARCHAR(255),
    ip VARCHAR(45),  -- IPv6 max length
    os VARCHAR(50),
    limit_ad_tracking BOOLEAN DEFAULT FALSE,
    processed_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- Create indexes for common query patterns
CREATE INDEX idx_bid_records_request_id ON bid_records(bid_request_id);
CREATE INDEX idx_bid_records_domain ON bid_records(domain);
CREATE INDEX idx_bid_records_processed_at ON bid_records(processed_at);

COMMENT ON TABLE bid_records IS 'Stores processed bid requests from Kafka stream';
COMMENT ON COLUMN bid_records.bid_request_id IS 'Original request ID from OpenRTB bid request';
COMMENT ON COLUMN bid_records.limit_ad_tracking IS 'True if user requested limited ad tracking (lmt=1)';

