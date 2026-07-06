environment = "staging"
project_id  = "nectrix-staging"
region      = "us-east1"

vpc_cidr      = "10.120.0.0/20"
pods_cidr     = "10.122.0.0/14"
services_cidr = "10.126.0.0/20"

gke_cluster_version = "1.31"
node_machine_type   = "e2-standard-4"
node_min_count      = 2
node_max_count      = 6
node_initial_count  = 2

cloudsql_tier         = "db-custom-4-15360"
cloudsql_disk_size_gb = 50

redis_memory_size_gb = 2
redis_tier           = "STANDARD_HA"

gcs_bucket_name = "nectrix-staging-objects"
