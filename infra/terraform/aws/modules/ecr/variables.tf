variable "name_prefix" {
  type = string
}

variable "repository_names" {
  description = "One ECR repository per deployable (core-app, copy-engine, broker-adapters, mt5-bridge-gateway)"
  type        = list(string)
  default     = ["core-app", "copy-engine", "broker-adapters", "mt5-bridge-gateway"]
}

variable "untagged_expiry_days" {
  description = "Expire untagged images older than this many days"
  type        = number
  default     = 7
}

variable "max_tagged_images" {
  description = "Keep at most this many tagged images per repository"
  type        = number
  default     = 30
}
