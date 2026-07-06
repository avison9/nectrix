variable "name_prefix" {
  description = "Prefix applied to all resource names (e.g. nectrix-dev)"
  type        = string
}

variable "vpc_cidr" {
  description = "CIDR block for the VPC"
  type        = string
}

variable "azs" {
  description = "Availability zones to spread subnets across"
  type        = list(string)
}

variable "single_nat_gateway" {
  description = "Use a single shared NAT gateway instead of one per AZ (cheaper, less available — fine for dev/staging)"
  type        = bool
  default     = true
}
