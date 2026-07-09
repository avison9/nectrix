#!/usr/bin/env bash
# TICKET-011 — creates the real "version 1" envelope-encryption KMS key in
# LocalStack (docker-compose.yml) and registers it as the current version in
# kms_key_versions, mirroring what infra/terraform/aws/modules/kms would
# provision for real (alias/<prefix>-envelope-v1). Idempotent — safe to
# re-run (e.g. after `docker compose down -v` wipes LocalStack's ephemeral
# state, which has no volume, same as every other local-only service here).
#
# Uses `awslocal` (bundled inside the localstack image) rather than requiring
# a host-level AWS CLI install — same "run the CLI inside the already-running
# service container" pattern as infra/kafka/create-topics.sh.
set -euo pipefail

ALIAS_NAME="alias/nectrix-local-envelope-v1"

echo "==> Checking for an existing '$ALIAS_NAME' key in LocalStack"
EXISTING_KEY_ID=$(docker compose exec -T localstack awslocal kms list-aliases \
  --query "Aliases[?AliasName=='$ALIAS_NAME'].TargetKeyId" --output text)

if [ -n "$EXISTING_KEY_ID" ] && [ "$EXISTING_KEY_ID" != "None" ]; then
  echo "==> Reusing existing key $EXISTING_KEY_ID"
else
  echo "==> Creating a new KMS key"
  KEY_ID=$(docker compose exec -T localstack awslocal kms create-key \
    --description "nectrix local envelope-encryption KEK, version 1" \
    --query "KeyMetadata.KeyId" --output text)
  echo "==> Created key $KEY_ID, aliasing as $ALIAS_NAME"
  docker compose exec -T localstack awslocal kms create-alias \
    --alias-name "$ALIAS_NAME" --target-key-id "$KEY_ID"
fi

echo "==> Registering version 1 in kms_key_versions (idempotent)"
docker compose exec -T postgres psql -U nectrix_app -d nectrix -v ON_ERROR_STOP=1 -c "
  INSERT INTO kms_key_versions (version, kms_key_id, is_current)
  VALUES (1, '$ALIAS_NAME', true)
  ON CONFLICT (version) DO NOTHING;
"

echo "==> Done. kms_key_versions:"
docker compose exec -T postgres psql -U nectrix_app -d nectrix -c "SELECT * FROM kms_key_versions;"
