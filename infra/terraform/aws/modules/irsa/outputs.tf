output "role_arns" {
  description = "Map of role key (cluster_autoscaler, aws_load_balancer_controller, app_storage_access) to IAM role ARN — annotate the matching ServiceAccount with eks.amazonaws.com/role-arn"
  value       = { for k, r in aws_iam_role.this : k => r.arn }
}
