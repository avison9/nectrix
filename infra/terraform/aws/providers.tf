provider "aws" {
  region = var.aws_region

  default_tags {
    tags = {
      Project     = "nectrix"
      Environment = var.environment
      ManagedBy   = "terraform"
    }
  }
}
