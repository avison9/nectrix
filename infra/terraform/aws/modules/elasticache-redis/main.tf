# AUTH token — same "state-only for now, real secrets manager is TICKET-011"
# pattern as ../rds-postgres/main.tf's master password.
resource "random_password" "auth_token" {
  length  = 32
  special = false
}

resource "aws_elasticache_subnet_group" "this" {
  name       = "${var.name_prefix}-redis"
  subnet_ids = var.private_subnet_ids
}

resource "aws_security_group" "this" {
  name        = "${var.name_prefix}-redis-sg"
  description = "Allow Redis access from the EKS cluster only"
  vpc_id      = var.vpc_id

  ingress {
    description     = "Redis from EKS cluster"
    from_port       = 6379
    to_port         = 6379
    protocol        = "tcp"
    security_groups = [var.allowed_security_group_id]
  }

  egress {
    description = "DNS to the VPC resolver - private subnet only, no public IP on this cluster"
    from_port   = 53
    to_port     = 53
    protocol    = "udp"
    cidr_blocks = [var.vpc_cidr]
  }

  tags = {
    Name = "${var.name_prefix}-redis-sg"
  }
}

# TICKET-008 — real Redis Cluster (sharded) mode, not TICKET-003's original
# single-shard, replica-only HA ("cluster mode disabled" in AWS's own
# terminology). num_node_groups (shard count) + replicas_per_node_group are
# mutually exclusive with num_cache_clusters — presence of num_node_groups is
# itself what enables cluster mode in current provider versions, no separate
# boolean. Flipping an already-applied replication group from
# num_cache_clusters to num_node_groups is a structural (force-new) change,
# not an online resize — irrelevant here (nothing has ever been applied) but
# a real operational note for whoever eventually runs this against a live
# environment.
resource "aws_elasticache_replication_group" "this" {
  replication_group_id = "${var.name_prefix}-redis"
  description          = "Nectrix ${var.name_prefix} Redis — idempotency keys, relationship-matching cache, rate limiting, live position cache"

  engine         = "redis"
  engine_version = var.engine_version
  node_type      = var.node_type

  num_node_groups         = var.num_node_groups
  replicas_per_node_group = var.replicas_per_node_group
  # No HA to fail over to if a shard has zero replicas.
  automatic_failover_enabled = var.replicas_per_node_group >= 1
  multi_az_enabled           = var.replicas_per_node_group >= 1

  subnet_group_name  = aws_elasticache_subnet_group.this.name
  security_group_ids = [aws_security_group.this.id]

  at_rest_encryption_enabled = true
  transit_encryption_enabled = true
  auth_token                 = random_password.auth_token.result

  log_delivery_configuration {
    destination      = aws_cloudwatch_log_group.slow_log.name
    destination_type = "cloudwatch-logs"
    log_format       = "json"
    log_type         = "slow-log"
  }

  tags = {
    Name = "${var.name_prefix}-redis"
  }
}

resource "aws_cloudwatch_log_group" "slow_log" {
  name              = "/nectrix/${var.name_prefix}/redis/slow-log"
  retention_in_days = 365
}
