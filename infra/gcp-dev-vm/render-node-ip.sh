#!/usr/bin/env bash
# Writes just the NODE_INTERNAL_IP line of docker-compose.yml's .env — not
# sensitive (it's also a public Terraform output, vm_internal_ip), safe to
# derive from the GCE metadata server on the VM itself rather than passed in
# over SSH. deploy-dev (main-pipeline.yml) appends the sensitive
# POSTGRES_PASSWORD/POSTGRES_APP_PASSWORD/MINIO_ROOT_PASSWORD lines
# separately, straight from GitHub Secrets, never through this script or
# instance metadata.
set -euo pipefail

NODE_INTERNAL_IP="$(curl -sf -H 'Metadata-Flavor: Google' \
  'http://metadata.google.internal/computeMetadata/v1/instance/network-interfaces/0/ip')"

echo "NODE_INTERNAL_IP=${NODE_INTERNAL_IP}" > "$(dirname "$0")/.env"
