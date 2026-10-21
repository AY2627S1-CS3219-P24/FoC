output "image_bucket_names" {
  description = "FOC_USER_BUCKET and FOC_SUPPLIER_BUCKET runtime values."
  value       = { for service, bucket in module.images : service => bucket.s3_bucket_id }
}

output "service_policy_arns" {
  description = "Each service policy is attached only to its owning ECS task role."
  value       = { for name, policy in aws_iam_policy.service_images : name => policy.arn }
}

output "deployment" {
  description = "Non-secret identifiers for release and operations."
  value = {
    desired_count               = var.start_services ? 1 : 0
    bootstrap_tasks             = module.ecs.bootstrap_task_arns
    bootstrap_log_group         = module.ecs.bootstrap_log_group
    admin_task_definition       = module.ecs.admin_task_definition
    databases                   = { for service, settings in local.services : service => settings.database }
    task_definitions            = module.ecs.task_definition_arns
    region                      = local.region
    cluster                     = module.ecs.cluster_name
    elastic_ip                  = module.network.elastic_ip
    database_host               = module.rds.address
    database_master_secret_arn  = module.rds.master_user_secret_arn
    configuration_bucket        = module.ecs.configuration_bucket
    repositories                = module.ecs.repository_urls
    core_capacity_provider      = module.ecs.core_capacity_provider
    parameter_prefix            = module.ecs.parameter_prefix
    grafana_cloudwatch_role_arn = module.ecs.grafana_cloudwatch_role_arn
    queues                      = module.messaging.queue_urls
    queue_names                 = module.messaging.queue_names
    topics                      = module.messaging.topic_arns
  }
}
