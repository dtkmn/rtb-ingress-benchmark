-- AdTech Demo Database Schema
-- This script runs automatically when PostgreSQL container starts

-- Enable pgvector extension (available in pgvector/pgvector image)
CREATE EXTENSION IF NOT EXISTS vector;

-- Create the bid_records table matching BidRecord.java entity
CREATE TABLE IF NOT EXISTS bid_records (
    id BIGSERIAL PRIMARY KEY,
    bid_request_id VARCHAR(255) NOT NULL,
    domain VARCHAR(255),
    app_bundle VARCHAR(255),
    ip VARCHAR(45),  -- IPv6 max length
    os VARCHAR(50),
    limit_ad_tracking BOOLEAN DEFAULT FALSE,
    processed_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    request_embedding vector(384) -- Common dimension for lightweight models
);

-- Create indexes for common query patterns
CREATE INDEX IF NOT EXISTS idx_bid_records_request_id ON bid_records(bid_request_id);
CREATE INDEX IF NOT EXISTS idx_bid_records_domain ON bid_records(domain);
CREATE INDEX IF NOT EXISTS idx_bid_records_processed_at ON bid_records(processed_at);
CREATE INDEX IF NOT EXISTS idx_bid_records_embedding ON bid_records USING hnsw (request_embedding vector_cosine_ops);

-- Grant permissions to the application user
GRANT ALL PRIVILEGES ON TABLE bid_records TO "user";
GRANT USAGE, SELECT ON SEQUENCE bid_records_id_seq TO "user";

-- Log completion
DO $$
BEGIN
    RAISE NOTICE 'AdTech database schema initialized successfully';
END $$;

