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

resource "aws_elasticache_replication_group" "this" {
  replication_group_id = "${var.name_prefix}-redis"
  description          = "Nectrix ${var.name_prefix} Redis — idempotency keys, relationship-matching cache, rate limiting, live position cache"

  engine         = "redis"
  engine_version = var.engine_version
  node_type      = var.node_type

  num_cache_clusters         = var.num_cache_clusters
  automatic_failover_enabled = var.num_cache_clusters >= 2
  multi_az_enabled           = var.num_cache_clusters >= 2

  subnet_group_name  = aws_elasticache_subnet_group.this.name
  security_group_ids = [aws_security_group.this.id]

  at_rest_encryption_enabled = true
  transit_encryption_enabled = true
  auth_token                 = random_password.auth_token.result

  tags = {
    Name = "${var.name_prefix}-redis"
  }
}
