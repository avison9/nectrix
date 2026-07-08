# TICKET-008 — the cluster-aware discovery endpoint (what a real Lettuce
# RedisClusterClient/go-redis ClusterClient bootstraps CLUSTER SLOTS
# discovery against). primary_endpoint_address is NOT populated once
# num_node_groups is set (only meaningful for a single-shard replication
# group) — this replaces it as the endpoint clients actually need.
output "configuration_endpoint" {
  value = aws_elasticache_replication_group.this.configuration_endpoint_address
}

output "port" {
  value = aws_elasticache_replication_group.this.port
}

output "auth_token" {
  value     = random_password.auth_token.result
  sensitive = true
}
