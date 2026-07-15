variable "project_id" {
  type = string
}

variable "region" {
  type    = string
  default = "europe-west1"
}

variable "state_bucket_name" {
  type    = string
  default = "nectrix-terraform-state-gcp-dev"
}
