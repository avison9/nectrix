# TICKET-007 — provisioned MSK, not MSK Serverless: Serverless forces
# SASL/IAM-only client authentication (a materially bigger lift — a real
# aws-msk-iam-auth SASL callback handler in both Kafka clients) — still
# genuinely unscoped future work, unrelated to what TICKET-011 actually
# delivered (a KMS module + envelope-encryption utility, ../kms). TLS-in-
# transit + the default AWS-managed at-rest key is the free, correct middle
# ground, mirroring exactly what ../elasticache-redis provides
# (transit_encryption_enabled = true) vs. still defers (CMK-backed at-rest
# encryption — see ../../.checkov.yaml) today.

resource "aws_security_group" "this" {
  name        = "${var.name_prefix}-kafka-sg"
  description = "Allow Kafka broker access from the EKS cluster only"
  vpc_id      = var.vpc_id

  ingress {
    description     = "Kafka (TLS) from EKS cluster"
    from_port       = 9094
    to_port         = 9094
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
    Name = "${var.name_prefix}-kafka-sg"
  }
}

resource "aws_cloudwatch_log_group" "broker_logs" {
  name              = "/nectrix/${var.name_prefix}/kafka/broker-logs"
  retention_in_days = 365
}

resource "aws_msk_cluster" "this" {
  cluster_name  = "${var.name_prefix}-kafka"
  kafka_version = var.kafka_version
  # Must be a multiple of the number of client_subnets (one broker per AZ,
  # replicated across brokers) — each subnet must also be in a distinct AZ,
  # which var.private_subnet_ids already guarantees (../networking creates
  # one private subnet per AZ).
  number_of_broker_nodes = var.number_of_broker_nodes

  broker_node_group_info {
    instance_type   = var.broker_instance_type
    client_subnets  = var.private_subnet_ids
    security_groups = [aws_security_group.this.id]

    storage_info {
      ebs_storage_info {
        volume_size = var.ebs_volume_size
      }
    }
  }

  encryption_info {
    encryption_in_transit {
      client_broker = "TLS"
      in_cluster    = true
    }
    # encryption_at_rest_kms_key_arn intentionally omitted — MSK always
    # encrypts at rest; omitting this uses the AWS-managed key rather than a
    # customer-managed one (see module comment above; matches
    # ../elasticache-redis's/../rds-postgres's identical CMK deferral).
  }

  logging_info {
    broker_logs {
      cloudwatch_logs {
        enabled   = true
        log_group = aws_cloudwatch_log_group.broker_logs.name
      }
    }
  }

  tags = {
    Name = "${var.name_prefix}-kafka"
  }
}
