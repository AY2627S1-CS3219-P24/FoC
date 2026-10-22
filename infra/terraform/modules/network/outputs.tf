output "public_subnet_id" {
  description = "Public subnet for ECS hosts."
  value       = aws_subnet.public.id
}

output "database_subnet_ids" {
  description = "Private subnets for the PostgreSQL subnet group."
  value       = [for subnet in aws_subnet.database : subnet.id]
}

output "compute_security_group_id" {
  description = "Security group for ECS hosts and the public gateway."
  value       = aws_security_group.compute.id
}

output "database_security_group_id" {
  description = "Security group allowing PostgreSQL from ECS hosts."
  value       = aws_security_group.database.id
}

output "eip_allocation_id" {
  description = "Allocation ID of the gateway Elastic IP."
  value       = aws_eip.gateway.id
}

output "elastic_ip" {
  description = "Public address associated with the gateway host."
  value       = aws_eip.gateway.public_ip
}

output "service_discovery_namespace_name" {
  description = "Private DNS namespace used by the gateway proxy."
  value       = aws_service_discovery_private_dns_namespace.app.name
}

output "service_discovery_service_arns" {
  description = "Cloud Map service ARNs keyed by application service."
  value       = { for name, service in aws_service_discovery_service.backend : name => service.arn }
}
