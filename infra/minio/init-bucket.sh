#!/usr/bin/env bash
# TICKET-101/TICKET-120 — creates the real MinIO buckets ArchivalBlobStorageClient (broker-account
# archival blobs) and AgreementDocumentStorageClient (signed management-agreement documents) each
# upload to, mirroring infra/localstack/init-kms.sh's own idempotent shape. Safe to re-run (e.g.
# after `docker compose down -v` wipes MinIO's ephemeral state).
#
# Uses `mc` (bundled inside the minio/minio image — already relied on by that service's own
# healthcheck, `mc ready local`) rather than requiring a host-level MinIO client install, same
# "run the CLI inside the already-running service container" pattern as
# infra/kafka/create-topics.sh/infra/localstack/init-kms.sh.
set -euo pipefail

ARCHIVAL_BUCKET_NAME="${ARCHIVAL_BUCKET:-nectrix-archival}"
DOCUMENTS_BUCKET_NAME="${DOCUMENTS_BUCKET:-nectrix-documents}"

echo "==> Configuring mc alias inside the minio container"
docker compose exec -T minio sh -c \
  'mc alias set local http://localhost:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD"'

for bucket in "$ARCHIVAL_BUCKET_NAME" "$DOCUMENTS_BUCKET_NAME"; do
  echo "==> Ensuring bucket '$bucket' exists"
  docker compose exec -T minio mc mb --ignore-existing "local/$bucket"
done

echo "==> Done. Buckets:"
docker compose exec -T minio mc ls local
