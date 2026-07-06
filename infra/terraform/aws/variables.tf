variable "environment" {
  description = "Environment name — must match the selected terraform workspace (enforced by the check block in main.tf)"
  type        = string

  validation {
    condition     = contains(["dev", "staging", "production"], var.environment)
    error_message = "environment must be one of: dev, staging, production."
  }
}

variable "aws_region" {
  type    = string
  default = "us-east-1"
}

variable "vpc_cidr" {
  description = "CIDR block for the VPC — must be a /16 (subnet math in modules/networking assumes this)"
  type        = string
}

variable "az_count" {
  type    = number
  default = 3
}

variable "single_nat_gateway" {
  description = "Use one shared NAT gateway instead of one per AZ — cheaper, less available. Recommended false for production."
  type        = bool
  default     = true
}

variable "eks_cluster_version" {
  type    = string
  default = "1.31"
}

variable "node_instance_types" {
  type = list(string)
}

variable "node_min_size" {
  type = number
}

variable "node_max_size" {
  type = number
}

variable "node_desired_size" {
  type = number
}

variable "rds_instance_class" {
  type = string
}

variable "rds_allocated_storage" {
  type    = number
  default = 100
}

variable "rds_engine_version" {
  type    = string
  default = "17.2"
}

variable "rds_backup_retention_days" {
  type    = number
  default = 7
}

variable "redis_node_type" {
  type = string
}

variable "redis_num_cache_clusters" {
  type    = number
  default = 2
}

variable "s3_bucket_name" {
  description = "Globally-unique S3 bucket name for this environment's object storage"
  type        = string
}

variable "github_repo" {
  description = "GitHub repo allowed to push images via OIDC (org/repo) — see modules/github-oidc"
  type        = string
  default     = "avison9/nectrix"
}
