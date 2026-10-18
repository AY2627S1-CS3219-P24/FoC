variable "region" {
  description = "AWS region for the service buckets."
  type        = string
  default     = "ap-southeast-2"
}
variable "user_bucket_name" {
  description = "Globally unique private bucket owned by user-service."
  type        = string
}
variable "supplier_bucket_name" {
  description = "Globally unique private bucket owned by supplier-service."
  type        = string
}
variable "name_prefix" {
  description = "Environment-specific prefix for IAM policies."
  type        = string
  default     = "foc-prod"
}
