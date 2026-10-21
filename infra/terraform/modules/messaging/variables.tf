variable "region" {
  description = "AWS region for messaging encryption and topic policies."
  type        = string
}

variable "name_prefix" {
  description = "Environment prefix for AWS resource names."
  type        = string
}

variable "service_names" {
  description = "Service names to provision SNS topics and SQS queues for."
  type        = set(string)
}

variable "event_subscriptions" {
  description = "SNS-to-SQS routes and allowed event types."
  type        = map(object({ publisher = string, consumer = string, event_types = set(string) }))
}
