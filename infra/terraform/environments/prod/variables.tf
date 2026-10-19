variable "region" {
  description = "AWS deployment region."
  type        = string
  default     = "ap-southeast-1"
  validation {
    condition     = var.region == "ap-southeast-1"
    error_message = "This deployment is configured for ap-southeast-1."
  }
}

variable "image_bucket_names" {
  description = "Globally unique image bucket names keyed by owning service."
  type        = map(string)
}

variable "name_prefix" {
  description = "Environment prefix for AWS resource names."
  type        = string
  default     = "foc-prod"
  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{1,30}[a-z0-9]$", var.name_prefix))
    error_message = "Use 3–32 lowercase letters, digits and hyphens, beginning with a letter and ending with a letter or digit."
  }
}

variable "domain_name" {
  description = "Public hostname for the gateway and its TLS certificate."
  type        = string
  validation {
    condition     = can(regex("^[a-zA-Z0-9][a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$", var.domain_name))
    error_message = "Set a public DNS name for the gateway."
  }
}

variable "acme_email" {
  description = "Email address for certificate registration and budget alerts."
  type        = string
  validation {
    condition     = can(regex("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$", var.acme_email))
    error_message = "Set a contact email for certificates and budget alerts."
  }
}

variable "mail_from" {
  description = "Sender address for account emails."
  type        = string
  validation {
    condition     = can(regex("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$", var.mail_from))
    error_message = "Set the mailbox address recipients see."
  }
}

variable "image_tag" {
  description = "Container image tag shared by all application services."
  type        = string
  validation {
    condition     = length(var.image_tag) > 0
    error_message = "Set image_tag explicitly."
  }
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
  validation {
    condition     = alltrue([for route in values(var.event_subscriptions) : length(route.event_types) > 0])
    error_message = "Routes must include event types."
  }
}

variable "github_oidc_provider_arn" {
  description = "Existing GitHub OIDC provider ARN; empty creates a provider."
  type        = string
  default     = ""
}

variable "grafana_cloudwatch_account_id" {
  description = "Grafana Cloud AWS account ID trusted to assume the CloudWatch read role; empty disables role creation."
  type        = string
  default     = ""
}

variable "grafana_cloudwatch_external_id" {
  description = "External ID required to assume the Grafana CloudWatch read role."
  type        = string
  default     = ""
  sensitive   = true
}
