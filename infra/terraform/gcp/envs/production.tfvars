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

redis_memory_size_gb = 8
redis_tier           = "STANDARD_HA"

kafka_vcpu_count   = 12
kafka_memory_bytes = 12884901888 # 12 GiB

gcs_bucket_name = "nectrix-production-objects"
