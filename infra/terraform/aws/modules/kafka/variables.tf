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
  description = "One subnet per broker/AZ — number_of_broker_nodes must be a multiple of this list's length"
  type        = list(string)
}

variable "allowed_security_group_id" {
  description = "Security group (typically the EKS cluster SG) allowed to reach Kafka on 9094 (TLS)"
  type        = string
}

variable "kafka_version" {
  type    = string
  default = "3.8.0" # matches docker-compose.yml's apache/kafka:3.8.0 dev/CI broker
}

variable "broker_instance_type" {
  type    = string
  default = "kafka.t3.small"
}

variable "number_of_broker_nodes" {
  description = "Must be a multiple of length(var.private_subnet_ids)"
  type        = number
  default     = 3
}

variable "ebs_volume_size" {
  description = "Per-broker EBS volume size, GiB"
  type        = number
  default     = 100
}
