output "static_ip" {
  value = google_compute_address.dev.address
}

output "instance_name" {
  value = google_compute_instance.dev.name
}

output "instance_self_link" {
  value = google_compute_instance.dev.self_link
}

output "service_account_email" {
  value = google_service_account.vm.email
}

output "internal_ip" {
  description = "The node's internal IP — how k3s pods reach the docker-compose stateful services running with network_mode: host (see deploy/overlays/dev/host-services) and what Kafka's advertised listener must use, since network_mode: host means \"kafka\"/\"localhost\" no longer resolve the way they do in local dev's bridge-networked docker-compose.yml."
  value       = google_compute_instance.dev.network_interface[0].network_ip
}
