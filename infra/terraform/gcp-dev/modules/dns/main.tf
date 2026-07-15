# A DELEGATED CHILD ZONE for dev.<domain>, not a zone for the apex domain
# itself — deliberately narrower than "move nectrix.app's DNS into Google
# Cloud DNS". The apex zone (and whatever MX/root records it carries today)
# stays exactly wherever it already lives; only the "dev" subtree is
# delegated here. The one manual step (see root README/outputs.tf): add an
# NS record for "dev" at whichever registrar/DNS provider holds the apex
# zone today, pointing at this zone's name_servers output. Standard DNS
# subdomain-delegation — the same mechanism that lets a completely different
# provider run staging.<domain> or any other subtree later without ever
# touching the apex zone.
resource "google_dns_managed_zone" "dev" {
  name        = "nectrix-dev"
  dns_name    = "${var.dev_subdomain}.${var.domain}."
  description = "Delegated dev.${var.domain} zone — nectrix-dev single-VM environment"
}

resource "google_dns_record_set" "subdomains" {
  for_each = toset(var.subdomain_hosts)

  name         = "${each.value}.${var.dev_subdomain}.${var.domain}."
  managed_zone = google_dns_managed_zone.dev.name
  type         = "A"
  ttl          = 300
  rrdatas      = [var.static_ip]
}
