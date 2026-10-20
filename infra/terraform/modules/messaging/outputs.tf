output "topic_arns" {
  description = "SNS topic ARNs keyed by publishing service."
  value       = { for name, topic in aws_sns_topic.events : name => topic.arn }
}

output "queue_urls" {
  description = "SQS queue URLs keyed by consuming service."
  value       = { for name, queue in aws_sqs_queue.events : name => queue.url }
}

output "queue_arns" {
  description = "SQS queue ARNs keyed by consuming service."
  value       = { for name, queue in aws_sqs_queue.events : name => queue.arn }
}
