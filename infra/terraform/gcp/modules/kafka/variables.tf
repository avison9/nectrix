variable "name_prefix" {
  type = string
}

variable "region" {
  type = string
}

variable "subnetwork_self_link" {
  type = string
}

variable "vcpu_count" {
  type    = number
  default = 3
}

variable "memory_bytes" {
  type    = number
  default = 3221225472 # 3 GiB — Managed Kafka's minimum is 1 GiB per vCPU, this gives headroom
}
