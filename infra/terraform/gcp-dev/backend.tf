# Local backend until infra/terraform/gcp-dev/bootstrap has been applied by
# hand, once, against a real project (creates the state bucket — see
# bootstrap/main.tf). Unlike infra/terraform/gcp (which stays local/unapplied
# indefinitely, see its own backend.tf), this module is meant to be applied
# for real almost immediately — state loss for a real VM + DNS zone is
# annoying enough to justify moving to the GCS backend as the very first
# step, not a someday-later migration.
#
# Once bootstrap/ has run, uncomment this and run `terraform init -migrate-state`:
#
# terraform {
#   backend "gcs" {
#     bucket = "nectrix-terraform-state-gcp-dev"
#     prefix = "gcp-dev/state"
#   }
# }
terraform {
  backend "local" {}
}
