environment = "production"
project_id  = "nectrix-production"
region      = "us-east1"

vpc_cidr      = "10.130.0.0/20"
pods_cidr     = "10.132.0.0/14"
services_cidr = "10.136.0.0/20"

gke_cluster_version = "1.31"
node_machine_type   = "n2-standard-4"
node_min_count      = 3
node_max_count      = 20
node_initial_count  = 3

cloudsql_tier         = "db-custom-8-30720"
cloudsql_disk_size_gb = 200

redis_psc_subnet_cidr   = "10.137.0.0/24" # outside vpc_cidr/pods_cidr/services_cidr above
redis_shard_count       = 3
redis_replica_count     = 1
redis_cluster_node_type = "REDIS_STANDARD_SMALL"

kafka_vcpu_count   = 12
kafka_memory_bytes = 12884901888 # 12 GiB

gcs_bucket_name = "nectrix-production-objects"
