variable "project_id" {
  type = string
}

variable "region" {
  type    = string
  default = "us-east1"
}

variable "state_bucket_name" {
  type    = string
  default = "nectrix-terraform-state-gcp"
}
