# Local backend for now — no real GCP project/bucket exists yet (this ticket is
# plan/validate-only, see infra/terraform/README.md). State is namespaced per
# workspace automatically under terraform.tfstate.d/<workspace>/, which is all
# AC2 ("isolated workspaces/state") requires at this stage.
terraform {
  backend "local" {}
}

# Once infra/terraform/gcp/bootstrap has been applied by hand, once, against a
# real project (creates the state bucket — see bootstrap/main.tf), switch to
# this and run `terraform init -migrate-state`:
#
# terraform {
#   backend "gcs" {
#     bucket = "nectrix-terraform-state-gcp"
#     prefix = "gcp/state"
#   }
# }
