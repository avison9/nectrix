locals {
  name_prefix = "nectrix-${var.environment}"
}

check "workspace_matches_environment" {
  assert {
    condition     = var.environment == terraform.workspace
    error_message = "var.environment ('${var.environment}') does not match the selected terraform workspace ('${terraform.workspace}'). Run `terraform workspace select ${var.environment}` or pass the matching -var-file."
  }
}

module "networking" {
  source = "./modules/networking"

  name_prefix   = local.name_prefix
  project_id    = var.project_id
  region        = var.region
  vpc_cidr      = var.vpc_cidr
  pods_cidr     = var.pods_cidr
  services_cidr = var.services_cidr
}

module "gke" {
  source = "./modules/gke"

  name_prefix          = local.name_prefix
  region               = var.region
  cluster_version      = var.gke_cluster_version
  network_self_link    = module.networking.network_self_link
  subnetwork_self_link = module.networking.subnetwork_self_link
  pods_range_name      = module.networking.pods_range_name
  services_range_name  = module.networking.services_range_name
  node_machine_type    = var.node_machine_type
  node_min_count       = var.node_min_count
  node_max_count       = var.node_max_count
  node_initial_count   = var.node_initial_count
}

module "cloudsql_postgres" {
  source = "./modules/cloudsql-postgres"

  name_prefix  = local.name_prefix
  region       = var.region
  network_id   = module.networking.network_id
  tier         = var.cloudsql_tier
  disk_size_gb = var.cloudsql_disk_size_gb

  depends_on = [module.networking]
}

module "memorystore_redis" {
  source = "./modules/memorystore-redis"

  name_prefix    = local.name_prefix
  region         = var.region
  network_id     = module.networking.network_id
  memory_size_gb = var.redis_memory_size_gb
  tier           = var.redis_tier

  depends_on = [module.networking]
}

module "gcs_storage" {
  source = "./modules/gcs-storage"

  bucket_name = var.gcs_bucket_name
  location    = var.region
}

module "workload_identity" {
  source = "./modules/workload-identity"

  name_prefix            = local.name_prefix
  workload_identity_pool = module.gke.workload_identity_pool
  gcs_bucket_name        = module.gcs_storage.bucket_name
}

module "glb_cloud_armor" {
  source = "./modules/glb-cloud-armor"

  name_prefix = local.name_prefix
}

module "artifact_registry" {
  source = "./modules/artifact-registry"

  name_prefix                    = local.name_prefix
  region                         = var.region
  github_repo                    = var.github_repo
  gke_node_service_account_email = module.gke.node_service_account_email
}
