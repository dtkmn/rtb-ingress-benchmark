# Quick Start Guide - Kafka KRaft Edition

## Prerequisites
- Docker Desktop running
- 8GB+ RAM available
- Ports available: 8070-8077, 9090, 9092, 3000, 5432, 9000

## Testing with Docker Compose

### 1. Clean Start
```bash
# Make sure Docker Desktop is running
docker ps

# Stop any existing containers
docker-compose down -v

# Kill any port conflicts
lsof -ti:9090,3000,16686 | xargs kill -9 2>/dev/null || true
```

### 2. Start Infrastructure First (Test KRaft)
```bash
# Start just Kafka to verify KRaft works
docker-compose up -d kafka

# Wait for Kafka to be ready (30-40 seconds)
sleep 40

# Verify Kafka is running in KRaft mode
docker-compose logs kafka | grep "KRaft"
# Should see: "Running in KRaft mode..."

# Check topic was created
docker-compose exec kafka kafka-topics --bootstrap-server localhost:9092 --list
# Should see: bids
```

### 3. Start Full Stack
```bash
# Start everything
docker-compose up -d

# Wait for all services to be healthy (60 seconds)
sleep 60

# Check status
docker-compose ps
```

### 4. Quick Test
```bash
# Test Quarkus Receiver
curl -X POST http://localhost:8070/bid-request \
  -H "Content-Type: application/json" \
  -d '{
    "id":"test-1",
    "impressionId":"imp-1",
    "price":1.5,
    "timestamp":"2024-11-18T08:00:00Z",
    "site":{"id":"site123"},
    "device":{"ip":"192.168.1.1","lmt":0}
  }'

# Should return: {"status":"accepted"}
```

### 5. Verify Data Flow
```bash
# Check messages in Kafka
docker-compose exec kafka kafka-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic bids \
  --from-beginning \
  --max-messages 1 \
  --timeout-ms 5000

# Check database (after ~5 seconds)
docker-compose exec postgres psql -U user -d postgres \
  -c "SELECT COUNT(*) FROM bid_records;"
```

## Automated Test

Run the comprehensive test script:

```bash
chmod +x scripts/test-kraft-stack.sh
./scripts/test-kraft-stack.sh
```

This will:
- ✅ Start all services
- ✅ Test the full receiver set, including Python, Spring Boot, and Node/Fastify
- ✅ Verify Kafka messages
- ✅ Check database persistence
- ✅ Show monitoring URLs

## Access Monitoring

Once everything is running:

- **Kafdrop** (Kafka UI): http://localhost:9000
- **Prometheus**: http://localhost:9090
- **Grafana**: http://localhost:3000
- **PostgreSQL**: localhost:5432 (user/password: user/password)

## Performance Testing

```bash
# Manual k6 run
BASE_URL=http://localhost:8070 VUS=100 DURATION=30s k6 run k6/load-test.js

# Run the full receiver matrix with benchmark defaults
scripts/run-benchmark-matrix.sh

# Fire-and-forget mode must be explicit
BENCHMARK_DELIVERY_MODE=enqueue BENCHMARK_KAFKA_ACKS=0 scripts/run-benchmark-matrix.sh

# HTTP-only mode isolates framework and JSON cost from Kafka
BENCHMARK_DELIVERY_MODE=http-only scripts/run-benchmark-matrix.sh

# Tighten the local benchmark budget explicitly
BENCHMARK_RECEIVER_CPUS=1.5 BENCHMARK_RECEIVER_MEMORY=512m scripts/run-benchmark-matrix.sh
```

`results/<timestamp>/summary.md` now includes normalized metrics in addition to raw throughput so you can compare both winner-by-throughput and winner-by-efficiency. When a compatible opposite-mode run exists, the collator also adds a matched mode-delta section and writes `mode-comparison.csv`.

For tighter apples-to-apples runs, make concurrency knobs explicit instead of relying on framework defaults:

```bash
HTTP_SERVER_WORKERS=2 \
GOMAXPROCS=2 \
QUARKUS_HTTP_IO_THREADS=2 \
SPRING_TOMCAT_THREADS_MAX=200 \
SPRING_TOMCAT_THREADS_MIN_SPARE=10 \
scripts/run-benchmark-matrix.sh
```

`HTTP_SERVER_WORKERS` is a runtime concurrency knob, not a universal CPU-thread setting. Python and Node use worker processes, Rust uses worker threads, and the actual CPU cap still comes from `BENCHMARK_RECEIVER_CPUS`.

For Kafka-in-the-path runs, keep the producer knobs explicit too:

```bash
BENCHMARK_KAFKA_LINGER_MS=10 \
BENCHMARK_KAFKA_BATCH_BYTES=131072 \
BENCHMARK_KAFKA_REQUEST_TIMEOUT_MS=5000 \
scripts/run-benchmark-matrix.sh
```

The default matrix now includes:

- `python-receiver` on port `8075`
- `spring-receiver` on port `8076`
- `node-receiver` on port `8077`

## Troubleshooting

### Port Already Allocated
```bash
# Find and kill process using the port
lsof -ti:9090 | xargs kill -9
```

### Kafka Not Starting
```bash
# Check logs
docker-compose logs kafka

# Look for "Running in KRaft mode..."
# If you see ZooKeeper errors, you're using old config
```

### Services Not Healthy
```bash
# Check individual service logs
docker-compose logs quarkus-receiver
docker-compose logs kafka

# Restart specific service
docker-compose restart quarkus-receiver
```

### No Messages in Database
```bash
# Check if sinker is running
docker-compose logs quarkus-sinker

# Check if topic exists
docker-compose exec kafka kafka-topics --bootstrap-server localhost:9092 --list
```

## Clean Up

```bash
# Stop all services
docker-compose down

# Stop and remove volumes (fresh start)
docker-compose down -v
```

## Next Steps

Once docker-compose works:
1. Deploy to Kubernetes: See [KUBERNETES.md](helm/KUBERNETES.md)
2. Set up GitOps: See [argocd/README.md](argocd/README.md)
3. Performance tuning: Adjust replicas in Helm values.yaml

## KRaft Benefits

Compared to the old ZooKeeper setup:
- ✅ One less container (Kafka only, no ZooKeeper)
- ✅ 50% less memory (512MB-1GB vs 2-4GB)
- ✅ 30% faster startup
- ✅ Simpler architecture
- ✅ Future-proof (ZooKeeper deprecated in Kafka 3.5+)
