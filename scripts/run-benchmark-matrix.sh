#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

ALL_SERVICES=(quarkus-receiver quarkus-receiver-native go-receiver rust-receiver python-receiver spring-receiver spring-virtual-receiver node-receiver)
STRICT_1_SERVICES=(quarkus-receiver quarkus-receiver-native go-receiver rust-receiver python-receiver spring-receiver node-receiver)

fail_guardrail() {
  echo "benchmark guardrail: $*" >&2
  exit 2
}

lower_value() {
  printf '%s' "$1" | tr '[:upper:]' '[:lower:]'
}

quarkus_platform_version_for_service() {
  local service_dir="$1"
  python3 - "$service_dir/pom.xml" <<'PY'
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

pom_path = Path(sys.argv[1])
try:
    root = ET.parse(pom_path).getroot()
except ET.ParseError as exc:
    print(f"could not parse {pom_path}: {exc}", file=sys.stderr)
    sys.exit(1)

namespace = {"m": "http://maven.apache.org/POM/4.0.0"}
properties = root.find("m:properties", namespace)
version = ""
if properties is not None:
    version = properties.findtext("m:quarkus.platform.version", default="", namespaces=namespace).strip()

if not version:
    print(f"{pom_path} is missing quarkus.platform.version", file=sys.stderr)
    sys.exit(1)

print(version)
PY
}

git_dirty_state() {
  if [[ -n "$(git status --porcelain)" ]]; then
    echo "true"
  else
    echo "false"
  fi
}

git_changed_files() {
  git status --porcelain | awk '{ print substr($0, 4) }' | paste -sd, -
}

service_set_includes_quarkus() {
  local service
  for service in "${SERVICES[@]}"; do
    case "$service" in
      quarkus-receiver|quarkus-receiver-native|quarkus-sinker) return 0 ;;
    esac
  done
  return 1
}

normalize_delivery_mode() {
  local candidate
  candidate="$(lower_value "${1:-confirm}")"
  case "$candidate" in
    ""|confirm) echo "confirm" ;;
    enqueue|http-only) echo "$candidate" ;;
    *) fail_guardrail "Unknown BENCHMARK_DELIVERY_MODE=$1; expected confirm, enqueue, or http-only" ;;
  esac
}

normalize_fairness_profile() {
  local candidate
  candidate="$(lower_value "${1:-fixed-envelope}")"
  case "$candidate" in
    strict-1|fixed-envelope|idiomatic) echo "$candidate" ;;
    *) fail_guardrail "Unknown BENCHMARK_FAIRNESS_PROFILE=$1; expected strict-1, fixed-envelope, or idiomatic" ;;
  esac
}

normalize_kafka_acks() {
  local candidate
  candidate="$(lower_value "${1:-1}")"
  case "$candidate" in
    ""|1|leader) echo "1" ;;
    0|none) echo "0" ;;
    -1|all) echo "all" ;;
    *) fail_guardrail "Unknown BENCHMARK_KAFKA_ACKS=$1; expected 0, 1, or all" ;;
  esac
}

derive_benchmark_preset() {
  if [[ -n "${BENCHMARK_PRESET:-}" ]]; then
    lower_value "$BENCHMARK_PRESET"
    return
  fi

  if [[ -n "${BENCHMARK_FAIRNESS_PROFILE:-}" ]]; then
    echo "custom"
    return
  fi

  if [[ -n "${BENCHMARK_DELIVERY_MODE:-}" ]]; then
    local requested_delivery_mode
    requested_delivery_mode="$(normalize_delivery_mode "$BENCHMARK_DELIVERY_MODE")"
    if [[ "$requested_delivery_mode" != "confirm" ]]; then
      echo "custom"
      return
    fi
  fi

  echo "strict-1"
}

BENCHMARK_PRESET="$(derive_benchmark_preset)"
case "$BENCHMARK_PRESET" in
  strict-1|custom) ;;
  *) fail_guardrail "Unknown BENCHMARK_PRESET=$BENCHMARK_PRESET; expected strict-1 or custom" ;;
esac

if [[ "$BENCHMARK_PRESET" == "strict-1" ]]; then
  if [[ -n "${BENCHMARK_DELIVERY_MODE:-}" ]] && [[ "$(normalize_delivery_mode "$BENCHMARK_DELIVERY_MODE")" != "confirm" ]]; then
    fail_guardrail "BENCHMARK_PRESET=strict-1 is a confirm benchmark preset; got BENCHMARK_DELIVERY_MODE=$BENCHMARK_DELIVERY_MODE"
  fi
  if [[ -n "${BENCHMARK_FAIRNESS_PROFILE:-}" ]] && [[ "$(normalize_fairness_profile "$BENCHMARK_FAIRNESS_PROFILE")" != "strict-1" ]]; then
    fail_guardrail "BENCHMARK_PRESET=strict-1 requires BENCHMARK_FAIRNESS_PROFILE=strict-1; got BENCHMARK_FAIRNESS_PROFILE=$BENCHMARK_FAIRNESS_PROFILE"
  fi
  DELIVERY_MODE="confirm"
  BENCHMARK_FAIRNESS_PROFILE="strict-1"
else
  DELIVERY_MODE="$(normalize_delivery_mode "${BENCHMARK_DELIVERY_MODE:-confirm}")"
  BENCHMARK_FAIRNESS_PROFILE="$(normalize_fairness_profile "${BENCHMARK_FAIRNESS_PROFILE:-fixed-envelope}")"
fi

if [[ -n "${BENCHMARK_SERVICES:-}" ]]; then
  SERVICES=(${BENCHMARK_SERVICES})
elif [[ "$BENCHMARK_PRESET" == "strict-1" ]]; then
  SERVICES=("${STRICT_1_SERVICES[@]}")
else
  SERVICES=("${ALL_SERVICES[@]}")
fi

OUT_DIR="${OUT_DIR:-$ROOT_DIR/results/$(date +%Y%m%d-%H%M%S)}"
REPEATS="${REPEATS:-3}"
BUILD_IMAGES="${BUILD_IMAGES:-1}"
BENCHMARK_VALIDATE_ONLY="${BENCHMARK_VALIDATE_ONLY:-0}"
BENCHMARK_CONFORMANCE_REPORT="${BENCHMARK_CONFORMANCE_REPORT:-}"
EFFECTIVE_CONFORMANCE_REPORT="${BENCHMARK_CONFORMANCE_REPORT:-$OUT_DIR/conformance/summary.json}"
if [[ "${BENCHMARK_CONFORMANCE:-0}" != "0" ]]; then
  fail_guardrail "conformance fault adapters must be disabled for comparative benchmark runs"
fi
WARMUP_DURATION="${WARMUP_DURATION:-10s}"
DURATION="${DURATION:-30s}"
VUS="${VUS:-100}"
RATE="${RATE:-0}"
LMT_PERCENT="${LMT_PERCENT:-0}"
BLOCKED_IP_PERCENT="${BLOCKED_IP_PERCENT:-0}"
BENCHMARK_RECEIVER_CPUSET="${BENCHMARK_RECEIVER_CPUSET:-}"
BENCHMARK_KAFKA_CPUSET="${BENCHMARK_KAFKA_CPUSET:-}"
BENCHMARK_KAFKA_TOPIC="${BENCHMARK_KAFKA_TOPIC:-}"
BENCHMARK_ENFORCE_PYTHON_RUST_CONFIRM_PARITY="${BENCHMARK_ENFORCE_PYTHON_RUST_CONFIRM_PARITY:-1}"

case "$BENCHMARK_VALIDATE_ONLY" in
  0|1) ;;
  *) fail_guardrail "BENCHMARK_VALIDATE_ONLY must be 0 or 1; got $BENCHMARK_VALIDATE_ONLY" ;;
esac

default_kafka_acks_for_mode() {
  case "$DELIVERY_MODE" in
    enqueue) echo "0" ;;
    *) echo "1" ;;
  esac
}

default_topic_for_preset() {
  case "$BENCHMARK_PRESET" in
    strict-1) echo "bids-strict-1" ;;
    *) echo "bids" ;;
  esac
}

default_parallelism_for_profile() {
  case "$BENCHMARK_FAIRNESS_PROFILE" in
    strict-1) echo "1" ;;
    *) derive_receiver_parallelism ;;
  esac
}

default_producer_pool_size_for_profile() {
  case "$BENCHMARK_FAIRNESS_PROFILE" in
    strict-1) echo "1" ;;
    *) echo "2" ;;
  esac
}

default_retries_for_profile() {
  case "$BENCHMARK_FAIRNESS_PROFILE" in
    strict-1) echo "0" ;;
    *) echo "5" ;;
  esac
}

default_topic_partitions_for_profile() {
  case "$BENCHMARK_FAIRNESS_PROFILE" in
    strict-1) echo "1" ;;
    *) echo "3" ;;
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

DEFAULT_RECEIVER_PARALLELISM="${BENCHMARK_DEFAULT_PARALLELISM:-$(default_parallelism_for_profile)}"
export HTTP_SERVER_WORKERS="${HTTP_SERVER_WORKERS:-$DEFAULT_RECEIVER_PARALLELISM}"
export GOMAXPROCS="${GOMAXPROCS:-$DEFAULT_RECEIVER_PARALLELISM}"
export QUARKUS_HTTP_IO_THREADS="${QUARKUS_HTTP_IO_THREADS:-$DEFAULT_RECEIVER_PARALLELISM}"
export BENCHMARK_KAFKA_ACKS="${BENCHMARK_KAFKA_ACKS:-$(default_kafka_acks_for_mode)}"
BENCHMARK_KAFKA_ACKS="$(normalize_kafka_acks "$BENCHMARK_KAFKA_ACKS")"
export BENCHMARK_KAFKA_ACKS
export BENCHMARK_KAFKA_LINGER_MS="${BENCHMARK_KAFKA_LINGER_MS:-10}"
export BENCHMARK_KAFKA_BATCH_BYTES="${BENCHMARK_KAFKA_BATCH_BYTES:-131072}"
export BENCHMARK_KAFKA_REQUEST_TIMEOUT_MS="${BENCHMARK_KAFKA_REQUEST_TIMEOUT_MS:-5000}"
export BENCHMARK_KAFKA_RETRIES="${BENCHMARK_KAFKA_RETRIES:-$(default_retries_for_profile)}"
export BENCHMARK_KAFKA_RETRY_BACKOFF_MS="${BENCHMARK_KAFKA_RETRY_BACKOFF_MS:-100}"
export BENCHMARK_KAFKA_PRODUCER_POOL_SIZE="${BENCHMARK_KAFKA_PRODUCER_POOL_SIZE:-$(default_producer_pool_size_for_profile)}"
export BENCHMARK_KAFKA_TOPIC="${BENCHMARK_KAFKA_TOPIC:-$(default_topic_for_preset)}"
export BENCHMARK_KAFKA_TOPIC_PARTITIONS="${BENCHMARK_KAFKA_TOPIC_PARTITIONS:-$(default_topic_partitions_for_profile)}"
export BENCHMARK_KAFKA_TOPIC_REPLICATION_FACTOR="${BENCHMARK_KAFKA_TOPIC_REPLICATION_FACTOR:-1}"
export BENCHMARK_KAFKA_TOPIC_MIN_ISR="${BENCHMARK_KAFKA_TOPIC_MIN_ISR:-1}"
export BENCHMARK_KAFKA_TOPIC_RETENTION_MS="${BENCHMARK_KAFKA_TOPIC_RETENTION_MS:-86400000}"
export BENCHMARK_KAFKA_DLQ_RETENTION_MS="${BENCHMARK_KAFKA_DLQ_RETENTION_MS:-604800000}"
export BENCHMARK_KAFKA_TOPIC_MAX_MESSAGE_BYTES="${BENCHMARK_KAFKA_TOPIC_MAX_MESSAGE_BYTES:-1048576}"
export BENCHMARK_KAFKA_BROKER_NUM_NETWORK_THREADS="${BENCHMARK_KAFKA_BROKER_NUM_NETWORK_THREADS:-8}"
export BENCHMARK_KAFKA_BROKER_NUM_IO_THREADS="${BENCHMARK_KAFKA_BROKER_NUM_IO_THREADS:-8}"
export BENCHMARK_KAFKA_SOCKET_REQUEST_MAX_BYTES="${BENCHMARK_KAFKA_SOCKET_REQUEST_MAX_BYTES:-104857600}"
export BENCHMARK_PIPELINE_MAX_RECORDS="${BENCHMARK_PIPELINE_MAX_RECORDS:-1024}"
export BENCHMARK_PIPELINE_MAX_BYTES="${BENCHMARK_PIPELINE_MAX_BYTES:-16777216}"
export BENCHMARK_PREPARATION_MAX_REQUESTS="${BENCHMARK_PREPARATION_MAX_REQUESTS:-128}"
export BENCHMARK_HTTP_OUTCOME_TIMEOUT_MS="${BENCHMARK_HTTP_OUTCOME_TIMEOUT_MS:-30000}"
export BENCHMARK_DRAIN_TIMEOUT_MS="${BENCHMARK_DRAIN_TIMEOUT_MS:-30000}"
export BENCHMARK_RECEIVER_STOP_GRACE_SECONDS="${BENCHMARK_RECEIVER_STOP_GRACE_SECONDS:-40}"
export BENCHMARK_TELEMETRY_PROFILE="${BENCHMARK_TELEMETRY_PROFILE:-controlled}"
export BENCHMARK_NATIVE_MAX_HEAP="${BENCHMARK_NATIVE_MAX_HEAP:-512m}"
export BENCHMARK_NATIVE_INITIAL_HEAP="${BENCHMARK_NATIVE_INITIAL_HEAP:-128m}"

if [[ "$BENCHMARK_TELEMETRY_PROFILE" != "controlled" ]]; then
  fail_guardrail "comparative runs require BENCHMARK_TELEMETRY_PROFILE=controlled; a matched operational profile has not been defined"
fi

python3 - <<'PY'
import os, re, sys
def heap(value):
    match = re.fullmatch(r'([0-9]+)([kKmMgG]?)', value)
    if not match or int(match[1]) < 1:
        sys.exit('benchmark guardrail: native heap arguments must be positive integer sizes with optional k/m/g suffix')
    return int(match[1]) * 1024 ** ('kmg'.index(match[2].lower()) + 1 if match[2] else 0)
if heap(os.environ['BENCHMARK_NATIVE_INITIAL_HEAP']) > heap(os.environ['BENCHMARK_NATIVE_MAX_HEAP']):
    sys.exit('benchmark guardrail: native initial heap must not exceed max heap')
PY

python3 - <<'PY'
import os, re, sys
names = ['BENCHMARK_PIPELINE_MAX_RECORDS', 'BENCHMARK_PIPELINE_MAX_BYTES',
         'BENCHMARK_PREPARATION_MAX_REQUESTS', 'BENCHMARK_HTTP_OUTCOME_TIMEOUT_MS',
         'BENCHMARK_DRAIN_TIMEOUT_MS', 'BENCHMARK_RECEIVER_STOP_GRACE_SECONDS']
for name in names:
    value = os.environ[name]
    if not re.fullmatch(r'[0-9]+', value) or not 0 < int(value) <= 2147483647:
        sys.exit(f'benchmark guardrail: {name} must be a positive 32-bit integer')
if int(os.environ['BENCHMARK_RECEIVER_STOP_GRACE_SECONDS']) * 1000 < int(os.environ['BENCHMARK_DRAIN_TIMEOUT_MS']) + 10000:
    sys.exit('benchmark guardrail: receiver stop grace must cover the drain timeout plus 10 seconds for teardown and process exit')
PY

if [[ "$DELIVERY_MODE" == "enqueue" && "${BENCHMARK_KAFKA_ACKS}" != "0" ]]; then
  echo "warning: enqueue mode is most comparable with BENCHMARK_KAFKA_ACKS=0; using explicit BENCHMARK_KAFKA_ACKS=${BENCHMARK_KAFKA_ACKS}" >&2
fi

service_is_enabled() {
  local target="$1"
  local service
  for service in "${SERVICES[@]}"; do
    if [[ "$service" == "$target" ]]; then
      return 0
    fi
  done
  return 1
}

known_service() {
  local service="$1"
  case "$service" in
    quarkus-receiver|quarkus-receiver-native|go-receiver|rust-receiver|python-receiver|spring-receiver|spring-virtual-receiver|node-receiver) return 0 ;;
    *) return 1 ;;
  esac
}

strict_1_supported_service() {
  local service="$1"
  case "$service" in
    quarkus-receiver|quarkus-receiver-native|go-receiver|rust-receiver|python-receiver|spring-receiver|node-receiver) return 0 ;;
    *) return 1 ;;
  esac
}

strict_1_unsupported_reason() {
  local service="$1"
  case "$service" in
    spring-virtual-receiver)
      echo "Spring virtual threads do not expose a clean one-HTTP-lane control; omit this lane or run BENCHMARK_PRESET=custom BENCHMARK_FAIRNESS_PROFILE=fixed-envelope for a resource-envelope experiment"
      ;;
    *)
      echo "this lane is not declared strict-1 compatible"
      ;;
  esac
}

validate_service_set() {
  if [[ "${#SERVICES[@]}" -eq 0 ]]; then
    fail_guardrail "BENCHMARK_SERVICES produced an empty service list"
  fi

  local service
  for service in "${SERVICES[@]}"; do
    if ! known_service "$service"; then
      fail_guardrail "Unknown service in BENCHMARK_SERVICES: $service"
    fi

    if [[ "$BENCHMARK_FAIRNESS_PROFILE" == "strict-1" ]] && ! strict_1_supported_service "$service"; then
      fail_guardrail "$service cannot satisfy BENCHMARK_FAIRNESS_PROFILE=strict-1 cleanly: $(strict_1_unsupported_reason "$service")"
    fi
  done
}

require_value() {
  local name="$1"
  local actual="$2"
  local expected="$3"
  local reason="$4"
  if [[ "$actual" != "$expected" ]]; then
    fail_guardrail "$name must be $expected for $reason; got $actual"
  fi
}

validate_confirm_guardrails() {
  if [[ "$BENCHMARK_FAIRNESS_PROFILE" == "strict-1" ]]; then
    require_value "HTTP_SERVER_WORKERS" "$HTTP_SERVER_WORKERS" "1" "strict-1 comparisons"
    require_value "GOMAXPROCS" "$GOMAXPROCS" "1" "strict-1 comparisons"
    require_value "QUARKUS_HTTP_IO_THREADS" "$QUARKUS_HTTP_IO_THREADS" "1" "strict-1 comparisons"
    if [[ "$DELIVERY_MODE" != "http-only" ]]; then
      require_value "BENCHMARK_KAFKA_PRODUCER_POOL_SIZE" "$BENCHMARK_KAFKA_PRODUCER_POOL_SIZE" "1" "strict-1 comparisons"
      require_value "BENCHMARK_KAFKA_TOPIC_PARTITIONS" "$BENCHMARK_KAFKA_TOPIC_PARTITIONS" "1" "strict-1 comparisons"
      require_value "BENCHMARK_KAFKA_RETRIES" "$BENCHMARK_KAFKA_RETRIES" "0" "strict-1 comparisons"
    fi
  fi
  if [[ "$DELIVERY_MODE" != "confirm" ]]; then
    return 0
  fi

  if [[ "$BENCHMARK_KAFKA_ACKS" == "0" ]]; then
    fail_guardrail "confirm mode must wait for broker acknowledgement; BENCHMARK_KAFKA_ACKS=$BENCHMARK_KAFKA_ACKS turns it into fire-and-forget"
  fi

  if [[ "$BENCHMARK_PRESET" == "strict-1" ]]; then
    require_value "BENCHMARK_KAFKA_ACKS" "$BENCHMARK_KAFKA_ACKS" "1" "BENCHMARK_PRESET=strict-1"
  fi

  if [[ "$BENCHMARK_FAIRNESS_PROFILE" == "strict-1" ]]; then
    require_value "LMT_PERCENT" "$LMT_PERCENT" "0" "strict-1 confirm comparisons"
    require_value "BLOCKED_IP_PERCENT" "$BLOCKED_IP_PERCENT" "0" "strict-1 confirm comparisons"
  fi

  if [[ "$BENCHMARK_ENFORCE_PYTHON_RUST_CONFIRM_PARITY" != "0" ]] \
    && service_is_enabled python-receiver \
    && service_is_enabled rust-receiver; then
    require_value "BENCHMARK_FAIRNESS_PROFILE" "$BENCHMARK_FAIRNESS_PROFILE" "strict-1" "Python/Rust confirm throughput parity"
    require_value "HTTP_SERVER_WORKERS" "$HTTP_SERVER_WORKERS" "1" "Python/Rust confirm throughput parity"
    require_value "BENCHMARK_KAFKA_PRODUCER_POOL_SIZE" "$BENCHMARK_KAFKA_PRODUCER_POOL_SIZE" "1" "Python/Rust confirm throughput parity"
    require_value "BENCHMARK_KAFKA_RETRIES" "$BENCHMARK_KAFKA_RETRIES" "0" "Python/Rust confirm throughput parity because aiokafka does not expose the same fixed retry-count knob"
    require_value "BENCHMARK_KAFKA_TOPIC_PARTITIONS" "$BENCHMARK_KAFKA_TOPIC_PARTITIONS" "1" "Python/Rust confirm throughput parity; this removes client-partitioner skew"
  fi
}

validate_service_set
validate_confirm_guardrails

QUARKUS_RECEIVER_PLATFORM_VERSION=""
QUARKUS_SINKER_PLATFORM_VERSION=""
QUARKUS_PLATFORM_VERSION=""
if service_set_includes_quarkus; then
  QUARKUS_RECEIVER_PLATFORM_VERSION="$(quarkus_platform_version_for_service "$ROOT_DIR/services/quarkus-receiver")"
  QUARKUS_SINKER_PLATFORM_VERSION="$(quarkus_platform_version_for_service "$ROOT_DIR/services/quarkus-sinker")"
  if [[ "$QUARKUS_RECEIVER_PLATFORM_VERSION" != "$QUARKUS_SINKER_PLATFORM_VERSION" ]]; then
    fail_guardrail "Quarkus platform versions must stay aligned: receiver=$QUARKUS_RECEIVER_PLATFORM_VERSION sinker=$QUARKUS_SINKER_PLATFORM_VERSION"
  fi
  QUARKUS_PLATFORM_VERSION="$QUARKUS_RECEIVER_PLATFORM_VERSION"
fi
GIT_DIRTY="$(git_dirty_state)"
GIT_CHANGED_FILES="$(git_changed_files)"

if [[ "$BENCHMARK_VALIDATE_ONLY" == "1" ]]; then
  echo "Benchmark configuration valid: preset=$BENCHMARK_PRESET fairness_profile=$BENCHMARK_FAIRNESS_PROFILE delivery_mode=$DELIVERY_MODE services=${SERVICES[*]}"
  exit 0
fi

command -v curl >/dev/null 2>&1 || fail_guardrail "curl is required for receiver readiness checks from the benchmark host"

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

wait_for_receiver_ready() {
  local service="$1"
  local base_url="$2"
  local health_url
  case "$service" in
    quarkus-receiver|quarkus-receiver-native) health_url="$base_url/q/health/ready" ;;
    *) health_url="$base_url/health" ;;
  esac

  local deadline=$((SECONDS + 180))
  local last_response="receiver container has not started"
  while (( SECONDS < deadline )); do
    local container_id
    container_id="$(docker compose ps -a -q "$service")"
    if [[ -n "$container_id" ]]; then
      local status
      status="$(docker inspect --format '{{.State.Status}}' "$container_id")"
      case "$status" in
        exited|dead)
          echo "Receiver $service exited before HTTP readiness (container status: $status)" >&2
          docker compose logs --tail 100 "$service" >&2 || true
          return 1
          ;;
        running)
          # Probe the published port used by k6. Distroless images do not need
          # an HTTP client inside the container for this readiness check.
          if last_response="$(curl --silent --show-error --noproxy '*' \
            --connect-timeout 2 --max-time 5 --output /dev/null \
            --write-out '%{http_code}' "$health_url" 2>&1)"; then
            case "$last_response" in
              2[0-9][0-9]) return 0 ;;
              *) : ;; # Retry non-successful responses until the deadline.
            esac
          fi
          ;;
        *) last_response="container status: $status" ;;
      esac
    fi
    sleep 2
  done

  echo "Timed out waiting for $service HTTP readiness at $health_url; last response: $last_response" >&2
  docker compose logs --tail 100 "$service" >&2 || true
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

kafka_topic_partition_count() {
  local topic="$1"
  docker compose exec -T kafka kafka-topics --bootstrap-server localhost:9092 --describe --topic "$topic" 2>/dev/null \
    | awk -F'PartitionCount:' 'NR == 1 { split($2, parts, " "); print parts[1] }'
}

validate_kafka_topic_guardrails() {
  if ! benchmark_uses_kafka; then
    return 0
  fi

  local actual_partitions
  docker compose exec -T kafka kafka-topics --bootstrap-server localhost:9092 \
    --describe --topic "$BENCHMARK_KAFKA_TOPIC" >"$OUT_DIR/kafka-topic-describe.txt"
  actual_partitions="$(kafka_topic_partition_count "$BENCHMARK_KAFKA_TOPIC")"
  if [[ -z "$actual_partitions" ]]; then
    fail_guardrail "could not inspect Kafka topic $BENCHMARK_KAFKA_TOPIC for BENCHMARK_PRESET=strict-1"
  fi

  require_value "Kafka topic $BENCHMARK_KAFKA_TOPIC partition count" "$actual_partitions" "$BENCHMARK_KAFKA_TOPIC_PARTITIONS" "effective topic configuration must match the declared profile"
  if [[ "$BENCHMARK_PRESET" == "strict-1" ]]; then
    require_value "Kafka topic $BENCHMARK_KAFKA_TOPIC partition count" "$actual_partitions" "1" "BENCHMARK_PRESET=strict-1; existing Docker volumes may contain an older wider topic"
  fi
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

validate_conformance_image_binding() {
  local service="$1"
  if ! python3 - "$service" "$EFFECTIVE_CONFORMANCE_REPORT" \
      "$OUT_DIR/$service-container-inspect.json" "$OUT_DIR/$service-conformance-image-binding.json" <<'PY'
import hashlib
import json
from pathlib import Path
import re
import sys

service, report_path, inspect_path, output_path = sys.argv[1:]
binding = {"schema_version": 1, "service": service, "passed": False,
           "conformance_report": str(Path(report_path).resolve()), "errors": []}
try:
    report_bytes = Path(report_path).read_bytes()
    binding["conformance_report_sha256"] = hashlib.sha256(report_bytes).hexdigest()
    report = json.loads(report_bytes)
    if not isinstance(report, dict) or type(report.get("schema_version")) is not int or report.get("schema_version") != 1 or report.get("passed") is not True or report.get("status") != "pass":
        raise ValueError("conformance report is not a successful schema-version-1 report")
    expected = report["images"][service]["id"]
    inspected = json.loads(Path(inspect_path).read_bytes())
    if not isinstance(inspected, list) or len(inspected) != 1 or not isinstance(inspected[0], dict):
        raise ValueError("actual container inspection must contain exactly one container")
    actual = inspected[0].get("Image")
    container_id = inspected[0].get("Id")
    binding.update(expected_image_id=expected, actual_image_id=actual, container_id=container_id)
    for value, pattern in ((expected, r"sha256:[0-9a-f]{64}"), (actual, r"sha256:[0-9a-f]{64}"), (container_id, r"[0-9a-f]{64}")):
        if not isinstance(value, str) or re.fullmatch(pattern, value) is None:
            raise ValueError("missing or malformed immutable image/container identity")
    if expected != actual:
        raise ValueError(f"actual timed container image {actual} differs from approved image {expected}")
    binding["passed"] = True
except (OSError, ValueError, KeyError, TypeError) as error:
    binding["errors"].append(str(error))
Path(output_path).write_text(json.dumps(binding, indent=2) + "\n")
if not binding["passed"]:
    print(f"{service} conformance image binding failed: " + "; ".join(binding["errors"]), file=sys.stderr)
    sys.exit(1)
PY
  then
    fail_guardrail "$service actual container is not bound to the verified conformance image; warm-up and timed work have not started for this service; see $OUT_DIR/$service-conformance-image-binding.json"
  fi
}

capture_and_validate_runtime() {
  local service="$1"
  local base_url="$2"
  local workers=1
  local cpuset_args=()
  if [[ -n "$BENCHMARK_RECEIVER_CPUSET" ]]; then
    cpuset_args=(--cpuset "$BENCHMARK_RECEIVER_CPUSET")
  fi
  case "$service" in
    node-receiver|python-receiver) workers="$HTTP_SERVER_WORKERS" ;;
  esac
  if ! python3 scripts/capture-receiver-runtime.py --base-url "$base_url" --workers "$workers" \
      --output "$OUT_DIR/$service-runtime.json"; then
    fail_guardrail "$service running configuration could not be captured; timed work has not started"
  fi
  if ! python3 scripts/validate-receiver-runtime.py --service "$service" \
      --runtime "$OUT_DIR/$service-runtime.json" --inspect "$OUT_DIR/$service-container-inspect.json" \
      --cpus "${BENCHMARK_RECEIVER_CPUS:-2.0}" --memory "${BENCHMARK_RECEIVER_MEMORY:-768m}" \
      ${cpuset_args[@]+"${cpuset_args[@]}"} \
      --fairness-profile "$BENCHMARK_FAIRNESS_PROFILE" --output "$OUT_DIR/$service-runtime-validation.json"; then
    fail_guardrail "$service effective configuration or optional-work evidence failed before timing; see runtime-validation.json"
  fi
  docker top "$(container_id_for "$service")" -eo pid,ppid,args >"$OUT_DIR/$service-processes.txt"
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


cat >"$OUT_DIR/run-meta.txt" <<EOF
timestamp=$(date -Iseconds)
git_sha=$(git rev-parse HEAD)
git_dirty=$GIT_DIRTY
git_changed_files=$GIT_CHANGED_FILES
uname=$(uname -a)
docker_version=$(docker version --format '{{.Server.Version}}')
compose_version=$(docker compose version --short)
quarkus_platform_version=$QUARKUS_PLATFORM_VERSION
quarkus_receiver_platform_version=$QUARKUS_RECEIVER_PLATFORM_VERSION
quarkus_sinker_platform_version=$QUARKUS_SINKER_PLATFORM_VERSION
services=${SERVICES[*]}
benchmark_preset=$BENCHMARK_PRESET
delivery_mode=$DELIVERY_MODE
kafka_acks=${BENCHMARK_KAFKA_ACKS}
kafka_enabled=$(if benchmark_uses_kafka; then echo true; else echo false; fi)
fairness_profile=$BENCHMARK_FAIRNESS_PROFILE
telemetry_profile=$BENCHMARK_TELEMETRY_PROFILE
native_max_heap_arg=$BENCHMARK_NATIVE_MAX_HEAP
native_initial_heap_arg=$BENCHMARK_NATIVE_INITIAL_HEAP
python_rust_confirm_parity_enforced=$BENCHMARK_ENFORCE_PYTHON_RUST_CONFIRM_PARITY
repeats=$REPEATS
build_images=$BUILD_IMAGES
conformance_report=$EFFECTIVE_CONFORMANCE_REPORT
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
kafka_producer_pool_size=${BENCHMARK_KAFKA_PRODUCER_POOL_SIZE:-}
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
pipeline_max_records=$BENCHMARK_PIPELINE_MAX_RECORDS
pipeline_max_bytes=$BENCHMARK_PIPELINE_MAX_BYTES
preparation_max_requests=$BENCHMARK_PREPARATION_MAX_REQUESTS
http_outcome_timeout_ms=$BENCHMARK_HTTP_OUTCOME_TIMEOUT_MS
drain_timeout_ms=$BENCHMARK_DRAIN_TIMEOUT_MS
receiver_stop_grace_seconds=$BENCHMARK_RECEIVER_STOP_GRACE_SECONDS
receiver_cpus=${BENCHMARK_RECEIVER_CPUS:-2.0}
receiver_memory=${BENCHMARK_RECEIVER_MEMORY:-768m}
receiver_cpuset=${BENCHMARK_RECEIVER_CPUSET:-}
kafka_cpus=${BENCHMARK_KAFKA_CPUS:-2.0}
kafka_memory=${BENCHMARK_KAFKA_MEMORY:-1g}
kafka_cpuset=${BENCHMARK_KAFKA_CPUSET:-}
EOF

if [[ -n "$BENCHMARK_CONFORMANCE_REPORT" ]]; then
  if ! python3 scripts/run-conformance-gate.py --services "${SERVICES[@]}" \
      --verify-report "$BENCHMARK_CONFORMANCE_REPORT"; then
    fail_guardrail "matching immutable-image conformance evidence failed; timed work has not started"
  fi
else
  conformance_build_args=()
  if [[ "$BUILD_IMAGES" != "0" ]]; then
    conformance_build_args=(--build)
  fi
  if ! python3 scripts/run-conformance-gate.py --services "${SERVICES[@]}" \
      ${conformance_build_args[@]+"${conformance_build_args[@]}"} --output "$OUT_DIR/conformance"; then
    fail_guardrail "required built-image conformance failed; timed work has not started; see $OUT_DIR/conformance"
  fi
fi

trap cleanup EXIT
if benchmark_uses_kafka; then
  docker compose up -d kafka
  wait_for_compose_health kafka
  wait_for_kafka_topic "$BENCHMARK_KAFKA_TOPIC"
  validate_kafka_topic_guardrails
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
  wait_for_receiver_ready "$service" "$base_url"
  apply_cpuset_if_requested "$service" "$BENCHMARK_RECEIVER_CPUSET"
  capture_container_inspect "$service"
  validate_conformance_image_binding "$service"
  capture_and_validate_runtime "$service" "$base_url"
  receiver_container_id="$(docker compose ps -q "$service")"
  receiver_started_at="$(docker inspect --format '{{.State.StartedAt}}' "$receiver_container_id")"
  curl --fail --silent --show-error --max-time 5 "$base_url/benchmark/state" >"$OUT_DIR/$service-initial-routed-state.json"

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
  docker compose logs --no-color --since "$receiver_started_at" "$service" >"$OUT_DIR/$service-drain.log"
  drain_workers=1
  case "$service" in
    node-receiver|python-receiver) drain_workers="$HTTP_SERVER_WORKERS" ;;
  esac
  if ! python3 scripts/validate-receiver-drain.py "$OUT_DIR/$service-drain.log" \
      --expected-workers "$drain_workers" --output "$OUT_DIR/$service-drain.json" >/dev/null; then
    fail_guardrail "$service drain/delivery evidence failed; see $OUT_DIR/$service-drain.json and $OUT_DIR/$service-drain.log"
  fi
done

python3 scripts/collate-benchmark-results.py "$OUT_DIR"

echo "Benchmark results written to $OUT_DIR"
