plugin "google" {
  enabled = true
  version = "0.31.0"
  source  = "github.com/terraform-linters/tflint-ruleset-google"

  # deep_check defaults to false and MUST stay false — that is the one tflint
  # mode that calls real GCP APIs. This ticket's verification is fully offline
  # (no credentials, no real account) — see infra/terraform/README.md.
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
