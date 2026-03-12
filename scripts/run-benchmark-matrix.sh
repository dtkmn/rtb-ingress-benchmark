#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

SERVICES=(${BENCHMARK_SERVICES:-quarkus-receiver quarkus-receiver-native go-receiver rust-receiver python-receiver spring-receiver spring-virtual-receiver node-receiver})
OUT_DIR="${OUT_DIR:-$ROOT_DIR/results/$(date +%Y%m%d-%H%M%S)}"
REPEATS="${REPEATS:-3}"
BUILD_IMAGES="${BUILD_IMAGES:-1}"
WARMUP_DURATION="${WARMUP_DURATION:-10s}"
DURATION="${DURATION:-30s}"
VUS="${VUS:-100}"
RATE="${RATE:-0}"
LMT_PERCENT="${LMT_PERCENT:-0}"
BLOCKED_IP_PERCENT="${BLOCKED_IP_PERCENT:-0}"
DELIVERY_MODE="${BENCHMARK_DELIVERY_MODE:-confirm}"
BENCHMARK_RECEIVER_CPUSET="${BENCHMARK_RECEIVER_CPUSET:-}"
BENCHMARK_KAFKA_CPUSET="${BENCHMARK_KAFKA_CPUSET:-}"
BENCHMARK_KAFKA_TOPIC="${BENCHMARK_KAFKA_TOPIC:-bids}"

default_kafka_acks_for_mode() {
  case "$DELIVERY_MODE" in
    enqueue) echo "0" ;;
    *) echo "1" ;;
  esac
}

derive_receiver_parallelism() {
  awk -v raw="${BENCHMARK_RECEIVER_CPUS:-2.0}" '
    BEGIN {
      value = raw + 0
      if (value < 1) {
        value = 1
      }
      parallelism = int(value)
      if (value > parallelism) {
        parallelism += 1
      }
      if (parallelism < 1) {
        parallelism = 1
      }
      print parallelism
    }
  '
}

DEFAULT_RECEIVER_PARALLELISM="${BENCHMARK_DEFAULT_PARALLELISM:-$(derive_receiver_parallelism)}"
export HTTP_SERVER_WORKERS="${HTTP_SERVER_WORKERS:-$DEFAULT_RECEIVER_PARALLELISM}"
export GOMAXPROCS="${GOMAXPROCS:-$DEFAULT_RECEIVER_PARALLELISM}"
export QUARKUS_HTTP_IO_THREADS="${QUARKUS_HTTP_IO_THREADS:-$DEFAULT_RECEIVER_PARALLELISM}"
export BENCHMARK_KAFKA_ACKS="${BENCHMARK_KAFKA_ACKS:-$(default_kafka_acks_for_mode)}"
export BENCHMARK_KAFKA_LINGER_MS="${BENCHMARK_KAFKA_LINGER_MS:-10}"
export BENCHMARK_KAFKA_BATCH_BYTES="${BENCHMARK_KAFKA_BATCH_BYTES:-131072}"
export BENCHMARK_KAFKA_REQUEST_TIMEOUT_MS="${BENCHMARK_KAFKA_REQUEST_TIMEOUT_MS:-5000}"
export BENCHMARK_KAFKA_RETRIES="${BENCHMARK_KAFKA_RETRIES:-5}"
export BENCHMARK_KAFKA_RETRY_BACKOFF_MS="${BENCHMARK_KAFKA_RETRY_BACKOFF_MS:-100}"
export BENCHMARK_KAFKA_TOPIC="${BENCHMARK_KAFKA_TOPIC:-bids}"
export BENCHMARK_KAFKA_TOPIC_PARTITIONS="${BENCHMARK_KAFKA_TOPIC_PARTITIONS:-3}"
export BENCHMARK_KAFKA_TOPIC_REPLICATION_FACTOR="${BENCHMARK_KAFKA_TOPIC_REPLICATION_FACTOR:-1}"
export BENCHMARK_KAFKA_TOPIC_MIN_ISR="${BENCHMARK_KAFKA_TOPIC_MIN_ISR:-1}"
export BENCHMARK_KAFKA_TOPIC_RETENTION_MS="${BENCHMARK_KAFKA_TOPIC_RETENTION_MS:-86400000}"
export BENCHMARK_KAFKA_DLQ_RETENTION_MS="${BENCHMARK_KAFKA_DLQ_RETENTION_MS:-604800000}"
export BENCHMARK_KAFKA_TOPIC_MAX_MESSAGE_BYTES="${BENCHMARK_KAFKA_TOPIC_MAX_MESSAGE_BYTES:-1048576}"
export BENCHMARK_KAFKA_BROKER_NUM_NETWORK_THREADS="${BENCHMARK_KAFKA_BROKER_NUM_NETWORK_THREADS:-8}"
export BENCHMARK_KAFKA_BROKER_NUM_IO_THREADS="${BENCHMARK_KAFKA_BROKER_NUM_IO_THREADS:-8}"
export BENCHMARK_KAFKA_SOCKET_REQUEST_MAX_BYTES="${BENCHMARK_KAFKA_SOCKET_REQUEST_MAX_BYTES:-104857600}"

if [[ "$DELIVERY_MODE" == "enqueue" && "${BENCHMARK_KAFKA_ACKS}" != "0" ]]; then
  echo "warning: enqueue mode is most comparable with BENCHMARK_KAFKA_ACKS=0; using explicit BENCHMARK_KAFKA_ACKS=${BENCHMARK_KAFKA_ACKS}" >&2
fi

mkdir -p "$OUT_DIR"

cleanup() {
  docker compose stop "${SERVICES[@]}" kafka >/dev/null 2>&1 || true
}

container_id_for() {
  docker compose ps -q "$1"
}

benchmark_uses_kafka() {
  [[ "$DELIVERY_MODE" != "http-only" ]]
}

wait_for_compose_health() {
  local service="$1"
  local deadline=$((SECONDS + 180))

  while (( SECONDS < deadline )); do
    local container_id
    container_id="$(docker compose ps -q "$service")"
    if [[ -n "$container_id" ]]; then
      local status
      status="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$container_id")"
      case "$status" in
        healthy|running)
          return 0
          ;;
        unhealthy|exited|dead)
          echo "Service $service failed with status $status" >&2
          docker compose logs "$service" >&2 || true
          return 1
          ;;
      esac
    fi
    sleep 2
  done

  echo "Timed out waiting for $service to become healthy" >&2
  docker compose logs "$service" >&2 || true
  return 1
}

wait_for_kafka_topic() {
  local topic="$1"
  local deadline=$((SECONDS + 180))

  while (( SECONDS < deadline )); do
    if docker compose exec -T kafka kafka-topics --bootstrap-server localhost:9092 --list 2>/dev/null | grep -qx "$topic"; then
      return 0
    fi
    sleep 2
  done

  echo "Timed out waiting for Kafka topic $topic" >&2
  docker compose logs kafka >&2 || true
  return 1
}

service_url() {
  case "$1" in
    quarkus-receiver) echo "http://localhost:8070" ;;
    quarkus-receiver-native) echo "http://localhost:8071" ;;
    go-receiver) echo "http://localhost:8072" ;;
    rust-receiver) echo "http://localhost:8073" ;;
    python-receiver) echo "http://localhost:8075" ;;
    spring-receiver) echo "http://localhost:8076" ;;
    spring-virtual-receiver) echo "http://localhost:8078" ;;
    node-receiver) echo "http://localhost:8077" ;;
    *)
      echo "Unknown service: $1" >&2
      return 1
      ;;
  esac
}

apply_cpuset_if_requested() {
  local service="$1"
  local cpuset="$2"
  if [[ -z "$cpuset" ]]; then
    return 0
  fi

  docker update --cpuset-cpus "$cpuset" "$(container_id_for "$service")" >/dev/null
}

capture_container_inspect() {
  local service="$1"
  docker inspect "$(container_id_for "$service")" >"$OUT_DIR/$service-container-inspect.json"
}

start_stats_capture() {
  local output_file="$1"
  shift
  docker stats --format '{{json .}}' "$@" >"$output_file" &
  echo $!
}

stop_stats_capture() {
  local stats_pid="$1"
  kill "$stats_pid" >/dev/null 2>&1 || true
  wait "$stats_pid" 2>/dev/null || true
}

trap cleanup EXIT

cat >"$OUT_DIR/run-meta.txt" <<EOF
timestamp=$(date -Iseconds)
git_sha=$(git rev-parse HEAD)
uname=$(uname -a)
docker_version=$(docker version --format '{{.Server.Version}}')
compose_version=$(docker compose version --short)
services=${SERVICES[*]}
delivery_mode=$DELIVERY_MODE
kafka_acks=${BENCHMARK_KAFKA_ACKS}
kafka_enabled=$(if benchmark_uses_kafka; then echo true; else echo false; fi)
repeats=$REPEATS
build_images=$BUILD_IMAGES
vus=$VUS
duration=$DURATION
rate=$RATE
warmup_duration=$WARMUP_DURATION
lmt_percent=$LMT_PERCENT
blocked_ip_percent=$BLOCKED_IP_PERCENT
http_server_workers=${HTTP_SERVER_WORKERS:-}
default_receiver_parallelism=$DEFAULT_RECEIVER_PARALLELISM
go_max_procs=${GOMAXPROCS:-}
quarkus_http_io_threads=${QUARKUS_HTTP_IO_THREADS:-}
kafka_linger_ms=${BENCHMARK_KAFKA_LINGER_MS:-}
kafka_batch_bytes=${BENCHMARK_KAFKA_BATCH_BYTES:-}
kafka_request_timeout_ms=${BENCHMARK_KAFKA_REQUEST_TIMEOUT_MS:-}
kafka_retries=${BENCHMARK_KAFKA_RETRIES:-}
kafka_retry_backoff_ms=${BENCHMARK_KAFKA_RETRY_BACKOFF_MS:-}
kafka_topic=${BENCHMARK_KAFKA_TOPIC:-}
kafka_topic_partitions=${BENCHMARK_KAFKA_TOPIC_PARTITIONS:-}
kafka_topic_replication_factor=${BENCHMARK_KAFKA_TOPIC_REPLICATION_FACTOR:-}
kafka_topic_min_isr=${BENCHMARK_KAFKA_TOPIC_MIN_ISR:-}
kafka_topic_retention_ms=${BENCHMARK_KAFKA_TOPIC_RETENTION_MS:-}
kafka_dlq_retention_ms=${BENCHMARK_KAFKA_DLQ_RETENTION_MS:-}
kafka_topic_max_message_bytes=${BENCHMARK_KAFKA_TOPIC_MAX_MESSAGE_BYTES:-}
kafka_broker_num_network_threads=${BENCHMARK_KAFKA_BROKER_NUM_NETWORK_THREADS:-}
kafka_broker_num_io_threads=${BENCHMARK_KAFKA_BROKER_NUM_IO_THREADS:-}
kafka_socket_request_max_bytes=${BENCHMARK_KAFKA_SOCKET_REQUEST_MAX_BYTES:-}
receiver_cpus=${BENCHMARK_RECEIVER_CPUS:-2.0}
receiver_memory=${BENCHMARK_RECEIVER_MEMORY:-768m}
receiver_cpuset=${BENCHMARK_RECEIVER_CPUSET:-}
kafka_cpus=${BENCHMARK_KAFKA_CPUS:-2.0}
kafka_memory=${BENCHMARK_KAFKA_MEMORY:-1g}
kafka_cpuset=${BENCHMARK_KAFKA_CPUSET:-}
EOF

if [[ "$BUILD_IMAGES" != "0" ]]; then
  docker compose build "${SERVICES[@]}"
fi

if benchmark_uses_kafka; then
  docker compose up -d kafka
  wait_for_compose_health kafka
  wait_for_kafka_topic "$BENCHMARK_KAFKA_TOPIC"
  apply_cpuset_if_requested kafka "$BENCHMARK_KAFKA_CPUSET"
  capture_container_inspect kafka
fi

for service in "${SERVICES[@]}"; do
  base_url="$(service_url "$service")"

  echo "==> benchmarking $service at $base_url"
  if benchmark_uses_kafka; then
    docker compose up -d "$service"
  else
    docker compose up -d --no-deps "$service"
  fi
  wait_for_compose_health "$service"
  apply_cpuset_if_requested "$service" "$BENCHMARK_RECEIVER_CPUSET"
  capture_container_inspect "$service"

  if [[ "$WARMUP_DURATION" != "0s" ]]; then
    BASE_URL="$base_url" DURATION="$WARMUP_DURATION" VUS="$VUS" RATE="$RATE" \
      LMT_PERCENT="$LMT_PERCENT" BLOCKED_IP_PERCENT="$BLOCKED_IP_PERCENT" \
      k6 run --quiet k6/load-test.js >/dev/null
  fi

  receiver_container_id="$(container_id_for "$service")"
  kafka_container_id=""
  if benchmark_uses_kafka; then
    kafka_container_id="$(container_id_for kafka)"
  fi

  for run in $(seq 1 "$REPEATS"); do
    run_id="$(printf '%02d' "$run")"
    receiver_stats_file="$OUT_DIR/$service-run-$run_id-receiver-stats.ndjson"
    kafka_stats_file="$OUT_DIR/$service-run-$run_id-kafka-stats.ndjson"
    summary_file="$OUT_DIR/$service-run-$run_id-summary.json"
    text_file="$OUT_DIR/$service-run-$run_id.txt"

    receiver_stats_pid="$(start_stats_capture "$receiver_stats_file" "$receiver_container_id")"
    kafka_stats_pid=""
    if benchmark_uses_kafka; then
      kafka_stats_pid="$(start_stats_capture "$kafka_stats_file" "$kafka_container_id")"
    fi

    status=0
    BASE_URL="$base_url" DURATION="$DURATION" VUS="$VUS" RATE="$RATE" \
      LMT_PERCENT="$LMT_PERCENT" BLOCKED_IP_PERCENT="$BLOCKED_IP_PERCENT" \
      k6 run --summary-export "$summary_file" k6/load-test.js \
      | tee "$text_file" || status=$?

    stop_stats_capture "$receiver_stats_pid"
    if [[ -n "$kafka_stats_pid" ]]; then
      stop_stats_capture "$kafka_stats_pid"
    fi

    if (( status != 0 )); then
      exit "$status"
    fi
  done

  docker compose stop "$service" >/dev/null
done

python3 scripts/collate-benchmark-results.py "$OUT_DIR"

echo "Benchmark results written to $OUT_DIR"
