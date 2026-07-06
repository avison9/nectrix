variable "name_prefix" {
  type = string
}

variable "github_repo" {
  description = "GitHub repo allowed to assume this role, as org/repo (see the sub claim condition in main.tf)"
  type        = string
  default     = "avison9/nectrix"
}

variable "ecr_repository_arns" {
  description = "ECR repository ARNs this role may push to (../ecr's output)"
  type        = list(string)
}
