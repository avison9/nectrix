#!/usr/bin/env bash
# Runs once on first boot (GCE metadata startup-script). Idempotent-ish by
# nature of apt/curl-install scripts, but not designed to be re-run by hand —
# a real re-provision should go through `terraform taint`/replace, not a
# manual re-exec of this file.
set -euo pipefail

# openjdk-17-jdk-headless, not 21 — Debian 12 (bookworm)'s main repo only
# ships 17 (21 is backports-only). Fine here: this Java is for ad hoc
# operator debugging on the box only, not for running the app itself (every
# service's real JRE is bundled in its own container image) or for
# migrations (db-migration-dev runs ./gradlew :db:update on the GitHub
# Actions runner, tunneled to this VM's Postgres — see main-pipeline.yml —
# not on the VM).
apt-get update
apt-get install -y --no-install-recommends \
  ca-certificates curl gnupg git openjdk-17-jdk-headless python3 golang-go

# Docker Engine + Compose plugin — official convenience script, same
# distribution mechanism the devcontainer's own tooling install steps use
# for other pinned external downloads (Gradle, Go).
curl -fsSL https://get.docker.com | sh
usermod -aG docker "$(logname 2>/dev/null || echo root)" || true

# k3s — single-node, single-binary Kubernetes distribution. Traefik (bundled
# by default) becomes the one thing bound to 80/443 on this VM; disabling
# servicelb since the static external IP is already handled at the GCE level
# (google_compute_address), not by k3s's own LoadBalancer controller.
curl -sfL https://get.k3s.io | INSTALL_K3S_EXEC="--disable=servicelb" sh -

# So a non-root operator (via IAP SSH) can run kubectl without sudo.
mkdir -p /etc/rancher/k3s
chmod 755 /etc/rancher/k3s

# Landing spot for deploy-dev (main-pipeline.yml) to scp
# infra/gcp-dev-vm/docker-compose.yml + render-node-ip.sh + a generated .env
# (NODE_INTERNAL_IP plus the sensitive Postgres/MinIO passwords, straight
# from GitHub Secrets — never baked into this startup script or instance
# metadata) into on every deploy, and for deploy/overlays/dev's kustomize
# output to land alongside it.
mkdir -p /opt/nectrix-dev-vm
