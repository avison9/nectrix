output "vm_static_ip" {
  value = module.vm.static_ip
}

output "vm_instance_name" {
  value = module.vm.instance_name
}

output "vm_internal_ip" {
  description = "Set as the GCP_DEV_VM_INTERNAL_IP repo variable — deploy-dev substitutes it into deploy/overlays/dev/host-services' Endpoints objects (NODE_INTERNAL_IP_REPLACED_BY_CI sentinel) and into the docker-compose stack's Kafka advertised-listener config."
  value       = module.vm.internal_ip
}

output "dns_name_servers" {
  description = "Add these 4 as an NS record for the \"dev\" subdomain at whichever registrar/DNS provider manages the apex domain today — the one manual step (see modules/dns/outputs.tf)."
  value       = module.dns.name_servers
}

output "artifact_registry_url" {
  description = "Set as the GCP_ARTIFACT_REGISTRY_URL repo variable."
  value       = module.artifact_registry.repository_url
}

output "ci_push_service_account_email" {
  description = "Set as the GCP_CI_PUSH_SERVICE_ACCOUNT repo variable."
  value       = module.artifact_registry.ci_push_service_account_email
}

output "ci_deploy_service_account_email" {
  description = "Set as the GCP_CI_DEPLOY_SERVICE_ACCOUNT repo variable."
  value       = module.artifact_registry.ci_deploy_service_account_email
}

output "ci_workload_identity_provider" {
  description = "Set as the GCP_WORKLOAD_IDENTITY_PROVIDER repo variable."
  value       = module.artifact_registry.workload_identity_provider
}

output "subdomains" {
  description = "Full hostnames this environment serves, once DNS delegation (see dns_name_servers) is done."
  value       = [for h in ["app", "portal", "api", "kafka-ui", "minio"] : "${h}.${var.dev_subdomain}.${var.domain}"]
}
