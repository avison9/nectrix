environment = "dev"
project_id  = "nectrix-dev"
region      = "us-east1"

vpc_cidr      = "10.110.0.0/20"
pods_cidr     = "10.112.0.0/14"
services_cidr = "10.116.0.0/20"

gke_cluster_version = "1.31"
node_machine_type   = "e2-standard-2"
node_min_count      = 1
node_max_count      = 3
node_initial_count  = 1

cloudsql_tier         = "db-custom-2-7680"
cloudsql_disk_size_gb = 20

redis_psc_subnet_cidr   = "10.117.0.0/24" # outside vpc_cidr/pods_cidr/services_cidr above
redis_shard_count       = 2               # smallest real cluster-mode shard count
redis_replica_count     = 0               # cheapest — no HA in dev
redis_cluster_node_type = "REDIS_SHARED_CORE_NANO"

kafka_vcpu_count   = 3
kafka_memory_bytes = 3221225472 # 3 GiB — Managed Kafka's minimum ratio

gcs_bucket_name = "nectrix-dev-objects"
