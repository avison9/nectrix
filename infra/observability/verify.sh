#!/usr/bin/env bash
# Real, hands-on proof of TICKET-010's AC1-4 — see nectrix_plan's
# TICKET-010-observability-stack.md. Runs against the docker-compose
# observability stack (prometheus/grafana/loki/tempo/alertmanager/
# webhook-catcher) plus freshly-built core-app/copy-engine images, matching
# infra/kind/*/run.sh's precedent of a fully self-contained, real,
# hands-on-executed verification script.
#
# Prerequisites: docker compose services running (`make up`), core-app's
# schema migrated (`make db-migrate`), a `.env` with POSTGRES_APP_PASSWORD/
# JWT_SIGNING_SECRET/TWO_FACTOR_SECRET_ENCRYPTION_KEY set (see .env.example).
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/../.."

# shellcheck disable=SC1091
set -a && source .env && set +a

NETWORK=nectrix_default
# Must match infra/observability/prometheus.yml's static_configs targets
# exactly ("core-app:8080"/"copy-engine:8090") — Docker resolves container
# names as DNS hostnames on a user-defined bridge network, so Prometheus can
# only actually scrape these containers if they're named this.
CORE_APP_CONTAINER=core-app
COPY_ENGINE_CONTAINER=copy-engine
MASTER_ACCOUNT_ID="10000000-0000-0000-0000-000000000010"
FOLLOWER_ACCOUNT_ID="10000000-0000-0000-0000-000000000011"

cleanup() {
  echo "==> Cleaning up"
  docker rm -f "$CORE_APP_CONTAINER" "$COPY_ENGINE_CONTAINER" >/dev/null 2>&1 || true
  docker rmi nectrix-verify-core-app nectrix-verify-copy-engine >/dev/null 2>&1 || true
  psql_exec "
    DELETE FROM copied_trades WHERE copy_relationship_id = '${RELATIONSHIP_ID:-00000000-0000-0000-0000-000000000000}';
    DELETE FROM trade_signals WHERE master_broker_account_id = '$MASTER_ACCOUNT_ID';
    DELETE FROM copy_relationships WHERE master_broker_account_id = '$MASTER_ACCOUNT_ID';
    DELETE FROM follow_requests WHERE follower_user_id = '10000000-0000-0000-0000-000000000003';
    DELETE FROM master_profiles WHERE user_id = '10000000-0000-0000-0000-000000000002';
    DELETE FROM broker_accounts WHERE id IN ('$MASTER_ACCOUNT_ID', '$FOLLOWER_ACCOUNT_ID');
    DELETE FROM users WHERE id IN ('10000000-0000-0000-0000-000000000001','10000000-0000-0000-0000-000000000002','10000000-0000-0000-0000-000000000003');
  " >/dev/null 2>&1 || true
}
trap cleanup EXIT

psql_exec() {
  docker compose exec -T postgres psql -U nectrix_app -d nectrix -v ON_ERROR_STOP=1 -c "$1"
}

poll_until() {
  local description="$1" timeout_s="$2" check="$3"
  local waited=0
  until eval "$check" >/dev/null 2>&1; do
    if [ "$waited" -ge "$timeout_s" ]; then
      echo "FAILED: $description did not become true within ${timeout_s}s"
      return 1
    fi
    sleep 2
    waited=$((waited + 2))
  done
  echo "OK: $description"
}

echo "==> Seeding a minimal FK-satisfying fixture (fresh, verify-only UUIDs)"
psql_exec "
  INSERT INTO users (id, email, display_name, status) VALUES
    ('10000000-0000-0000-0000-000000000001', 'verify-admin@nectrix.dev', 'Verify Admin', 'ACTIVE')
  ON CONFLICT (id) DO NOTHING;
  INSERT INTO users (id, email, display_name, status, created_by_user_id) VALUES
    ('10000000-0000-0000-0000-000000000002', 'verify-master@nectrix.dev', 'Verify Master', 'ACTIVE', '10000000-0000-0000-0000-000000000001'),
    ('10000000-0000-0000-0000-000000000003', 'verify-follower@nectrix.dev', 'Verify Follower', 'ACTIVE', '10000000-0000-0000-0000-000000000001')
  ON CONFLICT (id) DO NOTHING;
  INSERT INTO broker_accounts (id, user_id, broker_type, broker_account_login, is_demo, currency, connection_role, credentials_ciphertext, credentials_key_version, connection_status) VALUES
    ('$MASTER_ACCOUNT_ID', '10000000-0000-0000-0000-000000000002', 'CTRADER', 'verify-master', TRUE, 'USD', 'MASTER_ONLY', '\x00', 1, 'CONNECTED'),
    ('$FOLLOWER_ACCOUNT_ID', '10000000-0000-0000-0000-000000000003', 'CTRADER', 'verify-follower', TRUE, 'USD', 'FOLLOWER_ONLY', '\x00', 1, 'CONNECTED')
  ON CONFLICT (id) DO NOTHING;
  INSERT INTO master_profiles (id, user_id, primary_broker_account_id, display_name, is_public) VALUES
    ('10000000-0000-0000-0000-000000000020', '10000000-0000-0000-0000-000000000002', '$MASTER_ACCOUNT_ID', 'Verify Master', TRUE)
  ON CONFLICT (id) DO NOTHING;
  INSERT INTO money_management_profiles (id, method, multiplier, rounding_mode) VALUES
    ('10000000-0000-0000-0000-000000000030', 'MULTIPLIER', 1.0, 'DOWN')
  ON CONFLICT (id) DO NOTHING;
  INSERT INTO risk_profiles (id, max_lot_per_trade, max_open_positions, max_slippage_pips) VALUES
    ('10000000-0000-0000-0000-000000000031', 5.0, 20, 5)
  ON CONFLICT (id) DO NOTHING;
  INSERT INTO follow_requests (id, follower_user_id, master_profile_id, follower_broker_account_id, proposed_money_management_profile_id, proposed_risk_profile_id, status, decided_by_user_id, decided_at) VALUES
    ('10000000-0000-0000-0000-000000000040', '10000000-0000-0000-0000-000000000003', '10000000-0000-0000-0000-000000000020', '$FOLLOWER_ACCOUNT_ID', '10000000-0000-0000-0000-000000000030', '10000000-0000-0000-0000-000000000031', 'APPROVED', '10000000-0000-0000-0000-000000000002', now())
  ON CONFLICT (id) DO NOTHING;
  INSERT INTO copy_relationships (id, master_profile_id, master_broker_account_id, follower_user_id, follower_broker_account_id, money_management_profile_id, risk_profile_id, status, performance_fee_percent, fee_collection_method, originating_follow_request_id) VALUES
    ('10000000-0000-0000-0000-000000000050', '10000000-0000-0000-0000-000000000020', '$MASTER_ACCOUNT_ID', '10000000-0000-0000-0000-000000000003', '$FOLLOWER_ACCOUNT_ID', '10000000-0000-0000-0000-000000000030', '10000000-0000-0000-0000-000000000031', 'ACTIVE', 20.00, 'BROKER_PARTNERSHIP', '10000000-0000-0000-0000-000000000040')
  ON CONFLICT (id) DO NOTHING;
"
RELATIONSHIP_ID="10000000-0000-0000-0000-000000000050"

echo "==> Building core-app and copy-engine images"
docker build -q -f apps/core-app/Dockerfile -t nectrix-verify-core-app . >/dev/null
docker build -q -f apps/copy-engine/Dockerfile -t nectrix-verify-copy-engine . >/dev/null

echo "==> Starting core-app"
docker run --rm -d --name "$CORE_APP_CONTAINER" --network "$NETWORK" \
  -e POSTGRES_HOST=postgres -e POSTGRES_PORT=5432 -e POSTGRES_DB=nectrix -e POSTGRES_APP_PASSWORD="$POSTGRES_APP_PASSWORD" \
  -e REDIS_HOST=redis -e REDIS_PORT=6379 \
  -e JWT_SIGNING_SECRET="$JWT_SIGNING_SECRET" -e TWO_FACTOR_SECRET_ENCRYPTION_KEY="$TWO_FACTOR_SECRET_ENCRYPTION_KEY" \
  -e OTEL_EXPORTER_OTLP_ENDPOINT="http://tempo:4318" \
  nectrix-verify-core-app >/dev/null

echo "==> Starting copy-engine"
docker run --rm -d --name "$COPY_ENGINE_CONTAINER" --network "$NETWORK" \
  -e POSTGRES_HOST=postgres -e POSTGRES_PORT=5432 -e POSTGRES_DB=nectrix -e POSTGRES_APP_PASSWORD="$POSTGRES_APP_PASSWORD" \
  -e REDIS_HOST=redis -e REDIS_PORT=6379 \
  -e KAFKA_HOST=kafka -e KAFKA_PORT=29092 \
  -e OTEL_EXPORTER_OTLP_ENDPOINT="http://tempo:4318" \
  -e STUB_MASTER_BROKER_ACCOUNT_ID="$MASTER_ACCOUNT_ID" -e STUB_FOLLOWER_BROKER_ACCOUNT_ID="$FOLLOWER_ACCOUNT_ID" \
  nectrix-verify-copy-engine >/dev/null

curler() { docker run --rm --network "$NETWORK" curlimages/curl:8.11.1 "$@"; }

echo "==> Waiting for both services to be healthy"
poll_until "core-app /hello reachable" 60 "curler -sf http://$CORE_APP_CONTAINER:8080/hello"
poll_until "copy-engine /healthz reachable" 60 "curler -sf http://$COPY_ENGINE_CONTAINER:8090/healthz"

echo
echo "===== AC1: core-app hello-world request -> trace in Tempo + metrics in Prometheus ====="
curler -sf "http://$CORE_APP_CONTAINER:8080/hello" >/dev/null
poll_until "core-app trace visible in Tempo" 30 \
  "curl -sf 'http://localhost:3200/api/search?tags=service.name%3Dcore-app' | grep -q '\"rootTraceName\":\"GET /hello\"'"
poll_until "core-app metrics visible in Prometheus" 30 \
  "curl -sf 'http://localhost:9090/api/v1/query?query=http_server_requests_seconds_count%7Bjob%3D%22core-app%22%7D' | grep -q '\"value\"'"

echo
echo "===== AC2: synthetic event injected via stub adapter -> single end-to-end trace ====="
BROKER_POSITION_ID="verify-ac2-$(date +%s%N)"
curler -sf -X POST "http://$COPY_ENGINE_CONTAINER:8090/test/inject-trade-event" \
  -d "{\"brokerPositionId\":\"$BROKER_POSITION_ID\",\"volumeLots\":1.0}" >/dev/null
# Force an immediate trace flush rather than waiting on the batch timeout.
# -t 20 comfortably covers main.go's own drainSleep(10s)+shutdownWait(5s)
# budget before the tracer's own (fast) flush-on-shutdown runs.
docker stop -t 20 "$COPY_ENGINE_CONTAINER" >/dev/null
TRACE_ID=""
for _ in $(seq 1 15); do
  # Searching by nectrix.broker_position_id (a span attribute httpapi sets,
  # see internal/httpapi/httpapi.go), not just service.name+rootTraceName —
  # confirmed via direct testing that this repo's Tempo instance accumulates
  # many same-named "POST /test/inject-trade-event" traces across test runs,
  # and picking the first name-only match can return a stale/incomplete one.
  TRACE_ID=$(curl -sf "http://localhost:3200/api/search?tags=nectrix.broker_position_id%3D$BROKER_POSITION_ID" \
    | python3 -c "import sys,json; d=json.load(sys.stdin); print(d['traces'][0]['traceID'] if d.get('traces') else '')" 2>/dev/null || true)
  [ -n "$TRACE_ID" ] && break
  sleep 2
done
if [ -z "$TRACE_ID" ]; then
  echo "FAILED: no trace found for the injected event"
  exit 1
fi
SPAN_NAMES=$(curl -sf "http://localhost:3200/api/traces/$TRACE_ID" \
  | python3 -c "
import sys,json
d=json.load(sys.stdin)
names=set()
for b in d.get('batches', []):
    for ss in b.get('scopeSpans', []):
        for s in ss.get('spans', []):
            names.add(s['name'])
print(','.join(sorted(names)))
")
echo "spans in trace $TRACE_ID: $SPAN_NAMES"
for expected in "pipeline.normalize" "pipeline.dedup" "pipeline.relationship_match" "pipeline.dispatch_order" "pipeline.publish"; do
  case ",$SPAN_NAMES," in
    *",$expected,"*) echo "OK: span $expected present" ;;
    *) echo "FAILED: span $expected missing from trace"; exit 1 ;;
  esac
done
# Restart copy-engine for AC4 below.
docker run --rm -d --name "$COPY_ENGINE_CONTAINER" --network "$NETWORK" \
  -e POSTGRES_HOST=postgres -e POSTGRES_PORT=5432 -e POSTGRES_DB=nectrix -e POSTGRES_APP_PASSWORD="$POSTGRES_APP_PASSWORD" \
  -e REDIS_HOST=redis -e REDIS_PORT=6379 \
  -e KAFKA_HOST=kafka -e KAFKA_PORT=29092 \
  -e OTEL_EXPORTER_OTLP_ENDPOINT="http://tempo:4318" \
  -e STUB_MASTER_BROKER_ACCOUNT_ID="$MASTER_ACCOUNT_ID" -e STUB_FOLLOWER_BROKER_ACCOUNT_ID="$FOLLOWER_ACCOUNT_ID" \
  nectrix-verify-copy-engine >/dev/null
poll_until "copy-engine /healthz reachable again" 30 "curler -sf http://$COPY_ENGINE_CONTAINER:8090/healthz"

echo
echo "===== AC3: fake 'secret' field is redacted in the aggregated log view ====="
# core-app's /hello (AC1) and copy-engine's /healthz (both healthchecks
# above) already each logged one deliberately-fake "secret" field —
# promtail (docker-compose.yml) ships every container's stdout into Loki,
# queried here by container name.
LOKI_START_NS=$(( $(date -u +%s) * 1000000000 - 600000000000 )) # 10 minutes ago
poll_until "core-app's redacted secret visible in Loki" 30 \
  "curl -sfG 'http://localhost:3100/loki/api/v1/query_range' \
    --data-urlencode 'query={container=\"$CORE_APP_CONTAINER\"} |= \`hello endpoint hit\`' \
    --data-urlencode 'start=$LOKI_START_NS' | grep -q '\\\\\"secret\\\\\":\\\\\"\\*\\*\\*\\*\\\\\"'"

echo
echo "===== AC4: deliberately-triggered alert fires and reaches the webhook-catcher ====="
for _ in $(seq 1 10); do
  curler -s -o /dev/null -X POST "http://$COPY_ENGINE_CONTAINER:8090/test/inject-trade-event" \
    -d '{"serverTimestamp":"not-a-valid-timestamp"}' || true
done
poll_until "CopyEngineHighErrorRate alert is firing" 60 \
  "curl -sf 'http://localhost:9090/api/v1/alerts' | grep -q '\"alertname\":\"CopyEngineHighErrorRate\",.*\"state\":\"firing\"'"
poll_until "webhook-catcher received the Alertmanager POST" 60 \
  "curl -sf http://localhost:9099/received | grep -q CopyEngineHighErrorRate"

echo
echo "All TICKET-010 acceptance criteria verified."
