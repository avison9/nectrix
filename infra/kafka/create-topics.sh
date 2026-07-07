#!/usr/bin/env bash
# TICKET-007 — explicit topic catalog (docs/15-event-driven-architecture.md
# §15.3, plus the two invitation-onboarding additions from
# docs/05-domain-model.md §5.6: invitations, follow-requests), each with a
# real multi-partition layout so per-key ordering is a meaningful thing to
# test (Kafka's own auto.create.topics.enable default, which docker-compose.yml
# also has on, always creates single-partition topics — trivially "ordered"
# and useless for proving the partition-key design actually works). Every
# main topic also gets a `.dlq` variant (docs §15.6).
#
# Idempotent (--if-not-exists) — safe to run repeatedly against the same
# broker (local dev, CI). 3 partitions / replication-factor 1 for main
# topics (single local/CI broker — replication-factor >1 needs >1 broker);
# 1 partition for .dlq topics (no ordering requirement there, just capture).
set -euo pipefail

MAIN_TOPICS=(
  broker-connection
  trade-signals
  copied-trades
  copy-relationships
  billing
  partner
  risk
  invitations
  follow-requests
)

BOOTSTRAP_SERVER="localhost:29092" # internal listener — this script runs kafka-topics.sh INSIDE the kafka container
KAFKA_TOPICS_BIN="/opt/kafka/bin/kafka-topics.sh" # same bundled-CLI path docker-compose.yml's healthcheck already uses

create_topic() {
  local topic="$1"
  local partitions="$2"
  echo "==> Creating topic '$topic' (partitions=$partitions, replication-factor=1)"
  docker compose exec -T kafka "$KAFKA_TOPICS_BIN" \
    --bootstrap-server "$BOOTSTRAP_SERVER" \
    --create --if-not-exists \
    --topic "$topic" \
    --partitions "$partitions" \
    --replication-factor 1
}

for topic in "${MAIN_TOPICS[@]}"; do
  create_topic "$topic" 3
  create_topic "${topic}.dlq" 1
done

echo "==> Topic catalog:"
docker compose exec -T kafka "$KAFKA_TOPICS_BIN" --bootstrap-server "$BOOTSTRAP_SERVER" --list
