variable "name_prefix" {
  type = string
}

variable "vpc_id" {
  type = string
}

variable "vpc_cidr" {
  description = "VPC CIDR, for the DNS egress rule (see main.tf's aws_security_group.this)"
  type        = string
}

variable "private_subnet_ids" {
  type = list(string)
}

variable "allowed_security_group_id" {
  description = "Security group (typically the EKS cluster SG) allowed to reach Postgres on 5432"
  type        = string
}

variable "instance_class" {
  type = string
}

variable "allocated_storage" {
  type    = number
  default = 100
}

variable "engine_version" {
  description = "Postgres major.minor version (see docs/13-technology-stack.md §13.2 — PostgreSQL 17)"
  type        = string
  default     = "17.2"
}

variable "backup_retention_days" {
  type    = number
  default = 7
}

variable "db_name" {
  type    = string
  default = "nectrix"
}

variable "master_username" {
  type    = string
  default = "nectrix_admin"
}
