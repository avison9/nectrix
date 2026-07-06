# Master password: generated and stored only in Terraform state for now.
# KMS/secrets-manager envelope encryption for broker credentials is TICKET-011 —
# out of scope here; do not extend this pattern to broker-credential secrets.
resource "random_password" "master" {
  length  = 32
  special = false
}

resource "aws_db_subnet_group" "this" {
  name       = "${var.name_prefix}-postgres"
  subnet_ids = var.private_subnet_ids

  tags = {
    Name = "${var.name_prefix}-postgres-subnet-group"
  }
}

resource "aws_security_group" "this" {
  name        = "${var.name_prefix}-postgres-sg"
  description = "Allow Postgres access from the EKS cluster only"
  vpc_id      = var.vpc_id

  ingress {
    description     = "Postgres from EKS cluster"
    from_port       = 5432
    to_port         = 5432
    protocol        = "tcp"
    security_groups = [var.allowed_security_group_id]
  }

  egress {
    description = "HTTPS (AWS API/cert validation) - private subnet only, no public IP on this instance"
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  egress {
    description = "DNS to the VPC resolver"
    from_port   = 53
    to_port     = 53
    protocol    = "udp"
    cidr_blocks = [var.vpc_cidr]
  }

  tags = {
    Name = "${var.name_prefix}-postgres-sg"
  }
}

resource "aws_iam_role" "enhanced_monitoring" {
  name = "${var.name_prefix}-rds-monitoring"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Action    = "sts:AssumeRole"
      Principal = { Service = "monitoring.rds.amazonaws.com" }
    }]
  })
}

resource "aws_iam_role_policy_attachment" "enhanced_monitoring" {
  role       = aws_iam_role.enhanced_monitoring.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonRDSEnhancedMonitoringRole"
}

resource "aws_db_instance" "this" {
  identifier     = "${var.name_prefix}-postgres"
  engine         = "postgres"
  engine_version = var.engine_version
  instance_class = var.instance_class

  allocated_storage = var.allocated_storage
  storage_type      = "gp3"
  storage_encrypted = true

  db_name  = var.db_name
  username = var.master_username
  password = random_password.master.result

  multi_az               = true
  db_subnet_group_name   = aws_db_subnet_group.this.name
  vpc_security_group_ids = [aws_security_group.this.id]
  publicly_accessible    = false

  backup_retention_period   = var.backup_retention_days
  skip_final_snapshot       = false
  final_snapshot_identifier = "${var.name_prefix}-postgres-final"
  deletion_protection       = true

  auto_minor_version_upgrade          = true
  ca_cert_identifier                  = "rds-ca-rsa2048-g1"
  iam_database_authentication_enabled = true
  enabled_cloudwatch_logs_exports     = ["postgresql", "upgrade"]

  monitoring_interval = 60
  monitoring_role_arn = aws_iam_role.enhanced_monitoring.arn

  # Performance Insights uses the default AWS-owned key — a customer-managed
  # CMK here is TICKET-011 scope (KMS/secrets-manager), same as the master
  # password itself (see the comment at the top of this file).
  performance_insights_enabled = true

  tags = {
    Name = "${var.name_prefix}-postgres"
  }
}
