variable "name_prefix" {
  type = string
}

variable "region" {
  type = string
}

variable "network_id" {
  type = string
}

variable "psc_subnet_cidr" {
  description = "Dedicated subnet for Private Service Connect — must not overlap the GKE/pods/services CIDRs"
  type        = string
}

variable "shard_count" {
  type    = number
  default = 3
}

variable "replica_count" {
  type    = number
  default = 1
}

variable "node_type" {
  type    = string
  default = "REDIS_SHARED_CORE_NANO"
}
