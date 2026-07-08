# TICKET-008 — Memorystore for Redis Cluster (google_redis_cluster), a
# genuinely different resource from ../memorystore-redis's google_redis_instance
# — that resource has no cluster-mode option at all. Real Redis Cluster
# (sharded) mode, matching ../../aws/modules/elasticache-redis's equivalent
# num_node_groups change.
#
# Materially different networking model from ../memorystore-redis: that module
# uses Private Service Access (VPC peering, via ../networking's
# google_service_networking_connection) — google_redis_cluster instead uses
# Private Service Connect (PSC), a separate GCP networking primitive. This
# needs its own dedicated subnet (PSC requires an address range not shared
# with GKE/other workloads) plus a service-connection-policy resource, not
# just a reference to the existing VPC-peering setup.

resource "google_compute_subnetwork" "psc" {
  name          = "${var.name_prefix}-redis-cluster-psc"
  ip_cidr_range = var.psc_subnet_cidr
  region        = var.region
  network       = var.network_id
  purpose       = "PRIVATE_SERVICE_CONNECT"

  private_ip_google_access = true

  log_config {
    aggregation_interval = "INTERVAL_5_SEC"
    flow_sampling        = 0.5
    metadata             = "INCLUDE_ALL_METADATA"
  }
}

resource "google_network_connectivity_service_connection_policy" "this" {
  name          = "${var.name_prefix}-redis-cluster-scp"
  location      = var.region
  service_class = "gcp-memorystore-redis"
  network       = var.network_id

  psc_config {
    subnetworks = [google_compute_subnetwork.psc.id]
  }
}

resource "google_redis_cluster" "this" {
  name          = "${var.name_prefix}-redis-cluster"
  region        = var.region
  shard_count   = var.shard_count
  replica_count = var.replica_count
  node_type     = var.node_type

  # Matches ../memorystore-redis's posture (transit encryption + auth on) —
  # not the weaker defaults, for consistency with the rest of this module set.
  transit_encryption_mode = "TRANSIT_ENCRYPTION_MODE_SERVER_AUTHENTICATION"
  authorization_mode      = "AUTH_MODE_IAM_AUTH"

  psc_configs {
    network = var.network_id
  }

  depends_on = [google_network_connectivity_service_connection_policy.this]
}
