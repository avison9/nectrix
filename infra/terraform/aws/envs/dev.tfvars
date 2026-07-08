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

redis_node_type               = "cache.t3.micro"
redis_num_node_groups         = 2 # smallest real cluster-mode shard count
redis_replicas_per_node_group = 0 # cheapest — no HA in dev

# number_of_broker_nodes must be a multiple of az_count (one client_subnet
# per AZ) — dev's az_count=2 above means this can't stay at the module's
# default of 3.
kafka_broker_instance_type   = "kafka.t3.small"
kafka_number_of_broker_nodes = 2

s3_bucket_name = "nectrix-dev-objects"
