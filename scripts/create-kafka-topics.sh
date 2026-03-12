#!/bin/bash
set -euo pipefail

BOOTSTRAP_SERVER="${KAFKA_INTERNAL_BOOTSTRAP_SERVERS:-kafka:29092}"
BENCHMARK_TOPIC="${BENCHMARK_KAFKA_TOPIC:-bids}"
DLQ_TOPIC="${KAFKA_DLQ_TOPIC:-bids-dlq}"
TOPIC_PARTITIONS="${BENCHMARK_KAFKA_TOPIC_PARTITIONS:-3}"
TOPIC_REPLICATION_FACTOR="${BENCHMARK_KAFKA_TOPIC_REPLICATION_FACTOR:-1}"
TOPIC_MIN_ISR="${BENCHMARK_KAFKA_TOPIC_MIN_ISR:-1}"
TOPIC_RETENTION_MS="${BENCHMARK_KAFKA_TOPIC_RETENTION_MS:-86400000}"
DLQ_RETENTION_MS="${BENCHMARK_KAFKA_DLQ_RETENTION_MS:-604800000}"
TOPIC_MAX_MESSAGE_BYTES="${BENCHMARK_KAFKA_TOPIC_MAX_MESSAGE_BYTES:-1048576}"

wait_for_kafka() {
  echo "Waiting for Kafka to be ready at ${BOOTSTRAP_SERVER}..."
  cub kafka-ready -b "${BOOTSTRAP_SERVER}" 1 60
}

current_partition_count() {
  local topic="$1"
  kafka-topics --bootstrap-server "${BOOTSTRAP_SERVER}" --describe --topic "${topic}" 2>/dev/null \
    | awk -F'PartitionCount:' 'NR==1 { split($2, parts, " "); print parts[1] }'
}

configure_topic() {
  local topic="$1"
  local retention_ms="$2"

  kafka-configs --bootstrap-server "${BOOTSTRAP_SERVER}" \
    --alter \
    --entity-type topics \
    --entity-name "${topic}" \
    --add-config "cleanup.policy=delete,min.insync.replicas=${TOPIC_MIN_ISR},retention.ms=${retention_ms},max.message.bytes=${TOPIC_MAX_MESSAGE_BYTES}"
}

ensure_topic() {
  local topic="$1"
  local retention_ms="$2"

  kafka-topics --bootstrap-server "${BOOTSTRAP_SERVER}" \
    --create \
    --if-not-exists \
    --topic "${topic}" \
    --partitions "${TOPIC_PARTITIONS}" \
    --replication-factor "${TOPIC_REPLICATION_FACTOR}"

  local current_partitions
  current_partitions="$(current_partition_count "${topic}")"
  if [[ -n "${current_partitions}" && "${current_partitions}" -lt "${TOPIC_PARTITIONS}" ]]; then
    kafka-topics --bootstrap-server "${BOOTSTRAP_SERVER}" \
      --alter \
      --topic "${topic}" \
      --partitions "${TOPIC_PARTITIONS}"
  elif [[ -n "${current_partitions}" && "${current_partitions}" -gt "${TOPIC_PARTITIONS}" ]]; then
    echo "Topic ${topic} already has ${current_partitions} partitions; not shrinking to ${TOPIC_PARTITIONS}"
  fi

  configure_topic "${topic}" "${retention_ms}"
  kafka-topics --bootstrap-server "${BOOTSTRAP_SERVER}" --describe --topic "${topic}"
}

wait_for_kafka

echo "Ensuring Kafka topics are present and configured..."
ensure_topic "${BENCHMARK_TOPIC}" "${TOPIC_RETENTION_MS}"
ensure_topic "${DLQ_TOPIC}" "${DLQ_RETENTION_MS}"

echo "Kafka topics ready:"
kafka-topics --bootstrap-server "${BOOTSTRAP_SERVER}" --list
