# Single fixed environment — no workspace-per-env machinery like
# infra/terraform/gcp (that pattern exists there for dev/staging/production
# of the *managed* GKE/CloudSQL/Memorystore/Managed-Kafka architecture, which
# this module deliberately does not use — see infra/terraform/README.md and
# this module's own README.md for why).

locals {
  name_prefix     = "nectrix-dev"
  subdomain_hosts = ["app", "portal", "api", "kafka-ui", "minio"]
}

module "artifact_registry" {
  source = "./modules/artifact-registry"

  name_prefix = local.name_prefix
  project_id  = var.project_id
  region      = var.region
  github_repo = var.github_repo
}

module "vm" {
  source = "./modules/vm"

  name              = local.name_prefix
  region            = var.region
  zone              = var.zone
  machine_type      = var.machine_type
  boot_disk_size_gb = var.boot_disk_size_gb
  ssh_source_ranges = var.ssh_source_ranges

  artifact_registry_repository_id = module.artifact_registry.repository_id
  artifact_registry_location      = module.artifact_registry.location
  ci_deploy_service_account_email = module.artifact_registry.ci_deploy_service_account_email
}

module "dns" {
  source = "./modules/dns"

  domain          = var.domain
  dev_subdomain   = var.dev_subdomain
  static_ip       = module.vm.static_ip
  subdomain_hosts = local.subdomain_hosts
}
