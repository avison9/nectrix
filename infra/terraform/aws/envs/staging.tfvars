environment = "staging"
aws_region  = "us-east-1"
vpc_cidr    = "10.20.0.0/16"
az_count    = 3

single_nat_gateway = true

eks_cluster_version = "1.31"
node_instance_types = ["t3.large"]
node_min_size       = 2
node_max_size       = 6
node_desired_size   = 2

rds_instance_class        = "db.t3.large"
rds_allocated_storage     = 50
rds_backup_retention_days = 7

redis_node_type          = "cache.t3.small"
redis_num_cache_clusters = 2

s3_bucket_name = "nectrix-staging-objects"
