output "bootstrap_task_arns" {
  description = "Database bootstrap task definition ARNs keyed by service."
  value       = { for service, task in aws_ecs_task_definition.database_bootstrap : service => task.arn }
}

output "bootstrap_log_group" {
  description = "Log group for database bootstrap tasks."
  value       = aws_cloudwatch_log_group.database_bootstrap.name
}

output "task_definition_arns" {
  description = "Application task definition ARNs keyed by service."
  value = merge(
    { for name, service in module.app : name => service.task_definition_arn },
    { gateway = aws_ecs_task_definition.gateway.arn }
  )
}

output "cluster_name" {
  description = "ECS cluster name."
  value       = aws_ecs_cluster.app.name
}

output "configuration_bucket" {
  description = "Bucket holding the gateway TLS archive."
  value       = aws_s3_bucket.configuration.id
}

output "repository_urls" {
  description = "ECR repository URLs keyed by image name."
  value       = { for name, repository in aws_ecr_repository.app : name => repository.repository_url }
}

output "core_capacity_provider" {
  description = "Capacity provider for the core host."
  value       = aws_ecs_capacity_provider.host["core"].name
}

output "parameter_prefix" {
  description = "SSM parameter prefix for this environment."
  value       = local.parameter_prefix
}

output "grafana_cloudwatch_role_arn" {
  description = "Role Grafana Cloud assumes to read ECS, EC2, RDS metrics and application logs."
  value       = one(aws_iam_role.grafana_cloudwatch[*].arn)
}
