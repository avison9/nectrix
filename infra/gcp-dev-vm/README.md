# infra/gcp-dev-vm

Docker-compose stack for the nectrix-dev VM's stateful infra (Postgres,
Redis, Kafka, kafka-ui, MinIO) — see `infra/terraform/gcp-dev` for the VM
itself and `deploy/overlays/dev` for the k3s side that reaches these
services via headless Services pointed at the node's internal IP.

Not run locally, not part of any local-dev workflow — this is deployed to
`/opt/nectrix-dev-vm` on the real VM by `deploy-dev` (`.github/workflows/main-pipeline.yml`)
on every push to main:

1. `render-node-ip.sh` runs on the VM (queries the GCE metadata server,
   writes `NODE_INTERNAL_IP=...` into `.env`) — not sensitive, safe to derive
   on-box.
2. `deploy-dev` appends `POSTGRES_PASSWORD`, `POSTGRES_APP_PASSWORD`,
   `MINIO_ROOT_PASSWORD` to that same `.env` from GitHub Secrets, over the
   already-authenticated IAP SSH session — these values are never written to
   this repo or instance metadata.
3. `docker compose up -d` (idempotent — unchanged services aren't restarted).

See `infra/terraform/gcp-dev/README.md` for the full credentials/manual-steps
list this depends on.
