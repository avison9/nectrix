variable "name_prefix" {
  type = string
}

variable "region" {
  type = string
}

variable "network_id" {
  type = string
}

variable "memory_size_gb" {
  type    = number
  default = 4
}

variable "tier" {
  description = "BASIC (no HA) or STANDARD_HA"
  type        = string
  default     = "STANDARD_HA"
}

variable "redis_version" {
  type    = string
  default = "REDIS_7_2"
}
