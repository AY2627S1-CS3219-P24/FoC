variable "region" {
  description = "AWS deployment region."
  type        = string
}

variable "name_prefix" {
  description = "Environment prefix for AWS resource names."
  type        = string
}

variable "domain_name" {
  description = "Public hostname for the gateway and its TLS certificate."
  type        = string
}

variable "acme_email" {
  description = "Email address for certificate registration."
  type        = string
}

variable "mail_from" {
  description = "Sender address for account emails."
  type        = string
}

variable "start_services" {
  description = "Whether to run application services."
  type        = bool
}

variable "core_instance_type" {
  description = "EC2 instance type for the core ECS host."
  type        = string
}

variable "services" {
  description = "Service definitions and ECS task settings."
  type = map(object({
    port       = number
    cpu        = number
    memory     = number
    database   = string
    username   = string
    jwt        = bool
    mail       = bool
    bucket_env = optional(string)
  }))
}

variable "image_bucket_names" {
  description = "Existing image bucket names keyed by owning service."
  type        = map(string)
}

variable "image_bucket_policy_arns" {
  description = "Image access policy ARNs keyed by owning service."
  type        = map(string)
}

variable "public_subnet_id" {
  description = "Public subnet ID for ECS hosts."
  type        = string
}

variable "compute_security_group_id" {
  description = "Security group ID for ECS hosts."
  type        = string
}

variable "eip_allocation_id" {
  description = "Elastic IP allocation ID for the core host."
  type        = string
}

variable "service_discovery_namespace_name" {
  description = "Private Cloud Map DNS namespace name."
  type        = string
}

variable "service_discovery_service_arns" {
  description = "Cloud Map service ARNs keyed by service name."
  type        = map(string)
}

variable "topic_arns" {
  description = "SNS topic ARNs keyed by publishing service."
  type        = map(string)
}

variable "queue_arns" {
  description = "SQS queue ARNs keyed by consuming service."
  type        = map(string)
}

variable "database_host" {
  description = "PostgreSQL endpoint hostname."
  type        = string
}

variable "database_master_secret_arn" {
  description = "ARN of the Secrets Manager secret containing RDS master credentials."
  type        = string
}

variable "database_bootstrap_sql" {
  description = "Idempotent SQL for creating service roles and databases."
  type        = string
}

variable "grafana_cloudwatch_reader" {
  description = "Grafana Cloud account and external ID for the optional CloudWatch read role."
  type        = object({ account_id = string, external_id = string })
  default     = null
  sensitive   = true
}
