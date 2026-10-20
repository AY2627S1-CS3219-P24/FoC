variable "name_prefix" {
  description = "Environment prefix for AWS resource names."
  type        = string
}

variable "service_names" {
  description = "Service names to register in Cloud Map with SRV records."
  type        = set(string)
}
