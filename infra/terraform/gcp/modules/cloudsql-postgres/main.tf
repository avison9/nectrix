resource "random_password" "master" {
  length  = 32
  special = false
}

# Private IP connectivity depends on the Private Service Access peering
# connection created once, shared across Cloud SQL + Memorystore, in
# ../networking — the module block for this module in root main.tf carries an
# explicit `depends_on = [module.networking]` to sequence correctly.
resource "google_sql_database_instance" "this" {
  name             = "${var.name_prefix}-postgres"
  region           = var.region
  database_version = var.database_version

  deletion_protection = true

  settings {
    tier      = var.tier
    disk_size = var.disk_size_gb
    disk_type = "PD_SSD"

    availability_type = "REGIONAL"

    backup_configuration {
      enabled                        = true
      point_in_time_recovery_enabled = true
    }

    ip_configuration {
      ipv4_enabled    = false
      private_network = var.network_id
      ssl_mode        = "ENCRYPTED_ONLY"
    }

    database_flags {
      name  = "log_checkpoints"
      value = "on"
    }
    database_flags {
      name  = "log_connections"
      value = "on"
    }
    database_flags {
      name  = "log_disconnections"
      value = "on"
    }
    database_flags {
      name  = "log_lock_waits"
      value = "on"
    }
    database_flags {
      name  = "log_min_messages"
      value = "error"
    }
    database_flags {
      name  = "log_temp_files"
      value = "0"
    }
    database_flags {
      name  = "log_hostname"
      value = "on"
    }
    database_flags {
      name  = "log_statement"
      value = "ddl"
    }
    # Enables the pgAudit preload library — actually auditing still requires
    # `CREATE EXTENSION pgaudit;` inside the database itself (app-level SQL,
    # out of Terraform's scope here).
    database_flags {
      name  = "cloudsql.enable_pgaudit"
      value = "on"
    }
  }
}

resource "google_sql_database" "this" {
  name     = var.db_name
  instance = google_sql_database_instance.this.name
}

resource "google_sql_user" "this" {
  name     = var.db_user
  instance = google_sql_database_instance.this.name
  password = random_password.master.result
}
