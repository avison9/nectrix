locals {
  name_prefix = "nectrix-${var.environment}"
  azs         = slice(data.aws_availability_zones.available.names, 0, var.az_count)
}

data "aws_availability_zones" "available" {
  state = "available"
}

# Guards against the easy footgun of applying, e.g., envs/production.tfvars
# while the selected workspace is still "dev".
check "workspace_matches_environment" {
  assert {
    condition     = var.environment == terraform.workspace
    error_message = "var.environment ('${var.environment}') does not match the selected terraform workspace ('${terraform.workspace}'). Run `terraform workspace select ${var.environment}` or pass the matching -var-file."
  }
}

module "networking" {
  source = "./modules/networking"

  name_prefix        = local.name_prefix
  vpc_cidr           = var.vpc_cidr
  azs                = local.azs
  single_nat_gateway = var.single_nat_gateway
}

module "eks" {
  source = "./modules/eks"

  name_prefix         = local.name_prefix
  cluster_version     = var.eks_cluster_version
  vpc_id              = module.networking.vpc_id
  private_subnet_ids  = module.networking.private_subnet_ids
  node_instance_types = var.node_instance_types
  node_min_size       = var.node_min_size
  node_max_size       = var.node_max_size
  node_desired_size   = var.node_desired_size
}

module "rds_postgres" {
  source = "./modules/rds-postgres"

  name_prefix               = local.name_prefix
  vpc_id                    = module.networking.vpc_id
  vpc_cidr                  = var.vpc_cidr
  private_subnet_ids        = module.networking.private_subnet_ids
  allowed_security_group_id = module.eks.cluster_security_group_id
  instance_class            = var.rds_instance_class
  allocated_storage         = var.rds_allocated_storage
  engine_version            = var.rds_engine_version
  backup_retention_days     = var.rds_backup_retention_days
}

module "elasticache_redis" {
  source = "./modules/elasticache-redis"

  name_prefix               = local.name_prefix
  vpc_id                    = module.networking.vpc_id
  vpc_cidr                  = var.vpc_cidr
  private_subnet_ids        = module.networking.private_subnet_ids
  allowed_security_group_id = module.eks.cluster_security_group_id
  node_type                 = var.redis_node_type
  num_cache_clusters        = var.redis_num_cache_clusters
}

module "kafka" {
  source = "./modules/kafka"

  name_prefix               = local.name_prefix
  vpc_id                    = module.networking.vpc_id
  vpc_cidr                  = var.vpc_cidr
  private_subnet_ids        = module.networking.private_subnet_ids
  allowed_security_group_id = module.eks.cluster_security_group_id
  kafka_version             = var.kafka_version
  broker_instance_type      = var.kafka_broker_instance_type
  number_of_broker_nodes    = var.kafka_number_of_broker_nodes
}

module "s3_storage" {
  source = "./modules/s3-storage"

  bucket_name = var.s3_bucket_name
}

module "irsa" {
  source = "./modules/irsa"

  name_prefix          = local.name_prefix
  oidc_provider_arn    = module.eks.oidc_provider_arn
  oidc_provider_url    = module.eks.oidc_provider_url
  s3_access_policy_arn = module.s3_storage.access_policy_arn
}

module "alb_waf" {
  source = "./modules/alb-waf"

  name_prefix = local.name_prefix
  # alb_arn intentionally left null: the AWS Load Balancer Controller creates the
  # ALB from the gateway/admin-portal Ingress objects post-apply. Associate it as
  # a follow-up `terraform apply -var alb_arn=<arn>` once that ALB exists.
}

module "ecr" {
  source = "./modules/ecr"

  name_prefix = local.name_prefix
}

module "github_oidc" {
  source = "./modules/github-oidc"

  name_prefix         = local.name_prefix
  github_repo         = var.github_repo
  ecr_repository_arns = values(module.ecr.repository_arns)
}
