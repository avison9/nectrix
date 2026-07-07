environment = "production"
aws_region  = "us-east-1"
vpc_cidr    = "10.30.0.0/16"
az_count    = 3

single_nat_gateway = false

eks_cluster_version = "1.31"
node_instance_types = ["m6i.large"]
node_min_size       = 3
node_max_size       = 20
node_desired_size   = 3

rds_instance_class        = "db.r6g.large"
rds_allocated_storage     = 200
rds_backup_retention_days = 30

redis_node_type          = "cache.r6g.large"
redis_num_cache_clusters = 3

kafka_broker_instance_type   = "kafka.m5.large"
kafka_number_of_broker_nodes = 3 # matches az_count=3 above

s3_bucket_name = "nectrix-production-objects"
