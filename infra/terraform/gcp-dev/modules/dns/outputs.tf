output "name_servers" {
  description = "Give these 4 to whoever manages the apex domain's DNS — add them as an NS record for the \"dev\" subdomain. This is the one manual step; nothing about the apex zone's own records changes."
  value       = google_dns_managed_zone.dev.name_servers
}

output "zone_name" {
  value = google_dns_managed_zone.dev.name
}
