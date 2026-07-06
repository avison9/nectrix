# Local backend for now — no real AWS account/bucket exists yet (this ticket is
# plan/validate-only, see infra/terraform/README.md). State is namespaced per
# workspace automatically under terraform.tfstate.d/<workspace>/, which is all
# AC2 ("isolated workspaces/state") requires at this stage.
terraform {
  backend "local" {}
}

# Once infra/terraform/aws/bootstrap has been applied by hand, once, against a
# real account (creates the state bucket + lock table — see bootstrap/main.tf),
# switch to this and run `terraform init -migrate-state`:
#
# terraform {
#   backend "s3" {
#     bucket         = "nectrix-terraform-state-aws"
#     key            = "aws/terraform.tfstate"
#     region         = "us-east-1"
#     dynamodb_table = "nectrix-terraform-locks"
#     encrypt        = true
#   }
# }
