environment = "dev"
aws_region  = "us-east-1"
vpc_cidr    = "10.10.0.0/16"
az_count    = 2

single_nat_gateway = true

eks_cluster_version = "1.31"
node_instance_types = ["t3.medium"]
node_min_size       = 1
node_max_size       = 3
node_desired_size   = 1

rds_instance_class        = "db.t3.medium"
rds_allocated_storage     = 20
rds_backup_retention_days = 1

redis_node_type          = "cache.t3.micro"
redis_num_cache_clusters = 1

s3_bucket_name = "nectrix-dev-objects"
