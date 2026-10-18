output "bucket_names" {
  description = "FOC_USER_BUCKET and FOC_SUPPLIER_BUCKET runtime values."
  value       = { for service, bucket in module.images : service => bucket.s3_bucket_id }
}
output "service_policy_arns" {
  description = "Attach each policy to its service's ECS task role, or both to a shared EC2 instance role."
  value       = { for name, policy in aws_iam_policy.service_images : name => policy.arn }
}
