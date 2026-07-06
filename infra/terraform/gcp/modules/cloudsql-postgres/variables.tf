variable "name_prefix" {
  type = string
}

variable "region" {
  type = string
}

variable "network_id" {
  type = string
}

variable "tier" {
  description = "Cloud SQL machine tier, e.g. db-custom-2-7680"
  type        = string
}

variable "database_version" {
  type    = string
  default = "POSTGRES_17"
}

variable "disk_size_gb" {
  type    = number
  default = 100
}

variable "db_name" {
  type    = string
  default = "nectrix"
}

variable "db_user" {
  type    = string
  default = "nectrix_admin"
}
