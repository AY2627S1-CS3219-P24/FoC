variable "image_bucket_names" {
  description = "Globally unique image bucket names keyed by owning service."
  type        = object({ user = string, supplier = string })
}

variable "name_prefix" {
  description = "Environment prefix for AWS resource names."
  type        = string
  default     = "foc-prod"
}

variable "domain_name" {
  description = "Public hostname for the gateway and its TLS certificate."
  type        = string
}

variable "acme_email" {
  description = "Email address for certificate registration and budget alerts."
  type        = string
}

variable "mail_from" {
  description = "Sender address for account emails."
  type        = string
}

variable "start_services" {
  description = "Whether to run application services."
  type        = bool
  default     = false
}

variable "core_instance_type" {
  description = "EC2 instance type for the core ECS host."
  type        = string
  default     = "t3a.medium"
}

variable "db_instance_class" {
  description = "RDS PostgreSQL instance class."
  type        = string
  default     = "db.t4g.micro"
}

variable "event_subscriptions" {
  description = "SNS-to-SQS routes and allowed event types."
  type        = map(object({ publisher = string, consumer = string, event_types = set(string) }))
  default     = {}
}

variable "grafana_cloudwatch_reader" {
  description = "Grafana Cloud account and external ID for the optional CloudWatch read role."
  type        = object({ account_id = string, external_id = string })
  default     = null
  sensitive   = true
}
