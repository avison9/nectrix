plugin "google" {
  enabled = true
  version = "0.31.0"
  source  = "github.com/terraform-linters/tflint-ruleset-google"

  # deep_check defaults to false and MUST stay false for the shared `make
  # tf-lint` target — that is the one tflint mode that calls real GCP APIs,
  # and tf-lint runs credential-free across all of infra/terraform/*
  # (including infra/terraform/gcp, which is genuinely never applied — see
  # its own README). This module IS meant to be applied for real, but that
  # happens via a manual `terraform apply` with real credentials, not via
  # this offline lint target.
}

rule "terraform_deprecated_interpolation" {
  enabled = true
}

rule "terraform_unused_declarations" {
  enabled = true
}

rule "terraform_naming_convention" {
  enabled = true
  format  = "snake_case"
}

rule "terraform_required_version" {
  enabled = true
}

rule "terraform_required_providers" {
  enabled = true
}
