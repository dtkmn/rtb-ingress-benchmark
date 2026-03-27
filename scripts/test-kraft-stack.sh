#!/bin/bash
set -e

echo "🚀 Testing Kafka KRaft Stack..."
echo ""

# Colors
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo "${YELLOW}Step 1: Starting services...${NC}"
docker-compose up -d

echo ""
echo "${YELLOW}Step 2: Waiting for services to be healthy (60s)...${NC}"
sleep 60

echo ""
echo "${YELLOW}Step 3: Checking service status...${NC}"
docker-compose ps

echo ""
echo "${YELLOW}Step 4: Verifying Kafka topic...${NC}"
docker-compose exec kafka kafka-topics --bootstrap-server localhost:9092 --list

echo ""
echo "${YELLOW}Step 5: Testing Quarkus JVM Receiver...${NC}"
curl -s -X POST http://localhost:8070/bid-request \
  -H "Content-Type: application/json" \
  -d '{"id":"test-kraft-1","impressionId":"imp-1","price":1.5,"timestamp":"2024-11-18T08:00:00Z","site":{"id":"site123"},"device":{"ip":"192.168.1.1","lmt":0}}' \
  && echo "${GREEN}✅ Quarkus JVM Receiver OK${NC}" || echo "❌ Failed"

echo ""
echo "${YELLOW}Step 6: Testing Quarkus Native Receiver...${NC}"
curl -s -X POST http://localhost:8071/bid-request \
  -H "Content-Type: application/json" \
  -d '{"id":"test-kraft-2","impressionId":"imp-2","price":2.0,"timestamp":"2024-11-18T08:00:00Z","site":{"id":"site123"},"device":{"ip":"192.168.1.1","lmt":0}}' \
  && echo "${GREEN}✅ Quarkus Native Receiver OK${NC}" || echo "❌ Failed"

echo ""
echo "${YELLOW}Step 7: Testing Go Receiver...${NC}"
curl -s -X POST http://localhost:8072/bid-request \
  -H "Content-Type: application/json" \
  -d '{"id":"test-kraft-3","impressionId":"imp-3","price":2.5,"timestamp":"2024-11-18T08:00:00Z","site":{"id":"site123"},"device":{"ip":"192.168.1.1","lmt":0}}' \
  && echo "${GREEN}✅ Go Receiver OK${NC}" || echo "❌ Failed"

echo ""
echo "${YELLOW}Step 8: Testing Rust Receiver...${NC}"
curl -s -X POST http://localhost:8073/bid-request \
  -H "Content-Type: application/json" \
  -d '{"id":"test-kraft-4","impressionId":"imp-4","price":3.0,"timestamp":"2024-11-18T08:00:00Z","site":{"id":"site123"},"device":{"ip":"192.168.1.1","lmt":0}}' \
  && echo "${GREEN}✅ Rust Receiver OK${NC}" || echo "❌ Failed"

echo ""
echo "${YELLOW}Step 9: Testing Python Receiver...${NC}"
curl -s -X POST http://localhost:8075/bid-request \
  -H "Content-Type: application/json" \
  -d '{"id":"test-kraft-5","impressionId":"imp-5","price":3.5,"timestamp":"2024-11-18T08:00:00Z","site":{"id":"site123"},"device":{"ip":"192.168.1.1","lmt":0}}' \
  && echo "${GREEN}✅ Python Receiver OK${NC}" || echo "❌ Failed"

echo ""
echo "${YELLOW}Step 10: Testing Spring Receiver...${NC}"
curl -s -X POST http://localhost:8076/bid-request \
  -H "Content-Type: application/json" \
  -d '{"id":"test-kraft-6","impressionId":"imp-6","price":4.0,"timestamp":"2024-11-18T08:00:00Z","site":{"id":"site123"},"device":{"ip":"192.168.1.1","lmt":0}}' \
  && echo "${GREEN}✅ Spring Receiver OK${NC}" || echo "❌ Failed"

echo ""
echo "${YELLOW}Step 11: Testing Node Receiver...${NC}"
curl -s -X POST http://localhost:8077/bid-request \
  -H "Content-Type: application/json" \
  -d '{"id":"test-kraft-7","impressionId":"imp-7","price":4.5,"timestamp":"2024-11-18T08:00:00Z","site":{"id":"site123"},"device":{"ip":"192.168.1.1","lmt":0}}' \
  && echo "${GREEN}✅ Node Receiver OK${NC}" || echo "❌ Failed"

echo ""
echo "${YELLOW}Step 12: Checking Kafka messages...${NC}"
docker-compose exec kafka kafka-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic bids \
  --from-beginning \
  --max-messages 7 \
  --timeout-ms 5000 2>/dev/null && echo "${GREEN}✅ Messages in Kafka${NC}" || echo "⚠️  No messages yet"

echo ""
echo "${YELLOW}Step 13: Checking database records...${NC}"
sleep 5
docker-compose exec postgres psql -U user -d postgres -c "SELECT COUNT(*) as total_bids FROM bid_records;" \
  && echo "${GREEN}✅ Database OK${NC}" || echo "⚠️  Database not ready"

echo ""
echo "${GREEN}🎉 Kafka KRaft Stack Test Complete!${NC}"
echo ""
echo "📊 Access monitoring:"
echo "  - Kafdrop (Kafka UI):  http://localhost:9000"
echo "  - Prometheus:          http://localhost:9090"
echo "  - Grafana:             http://localhost:3000"
echo ""
echo "🔍 Service endpoints:"
echo "  - Quarkus JVM:         http://localhost:8070/bid-request"
echo "  - Quarkus Native:      http://localhost:8071/bid-request"
echo "  - Go Receiver:         http://localhost:8072/bid-request"
echo "  - Rust Receiver:       http://localhost:8073/bid-request"
echo "  - Python Receiver:     http://localhost:8075/bid-request"
echo "  - Spring Receiver:     http://localhost:8076/bid-request"
echo "  - Node Receiver:       http://localhost:8077/bid-request"
echo "  - Quarkus Sinker:      http://localhost:8074/q/health"
