# TICKET-011 — establishes the secrets-manager pattern for *service-to-service*
# credentials (DB connection strings, KMS access, message-bus auth), per
# docs/16-deployment-architecture.md §16.5 — a deliberately different
# mechanism from ../kms's application-level envelope encryption (which
# protects *user/broker data*, e.g. users.two_factor_secret). K8s Secrets/
# External Secrets Operator would pull from here in a real deployment; that
# wiring is future work — CI's current deploy-staging/deploy-production jobs
# still create their own throwaway K8s Secrets directly (main-pipeline.yml),
# untouched by this ticket. This one illustrative secret exists to prove the
# module shape offline-validates; it holds no real value yet.
resource "aws_secretsmanager_secret" "example" {
  name        = "${var.name_prefix}-service-credentials-example"
  description = "Placeholder illustrating this module's shape — real service-to-service secrets (DB connection strings, KMS access, message-bus auth) land here once the deploy pipeline is migrated off CI-injected K8s Secrets."
  # Reuses ../kms's envelope-encryption CMK rather than Secrets Manager's
  # AWS-owned default key — a real CMK key policy already exists to extend
  # (unlike this illustrative secret, not worth minting a second dedicated
  # CMK for). A real deployment might split these once real secrets land here.
  kms_key_id = var.kms_key_arn
}
