output "private_ip_address" {
  value = google_sql_database_instance.this.private_ip_address
}

output "db_name" {
  value = google_sql_database.this.name
}

output "db_user" {
  value = google_sql_user.this.name
}

output "db_password" {
  value     = random_password.master.result
  sensitive = true
}
