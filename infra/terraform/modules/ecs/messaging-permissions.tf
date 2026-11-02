locals {
  publisher_permissions = {
    for name, arn in var.topic_arns : name => [
      {
        Effect   = "Allow"
        Action   = ["sns:Publish"]
        Resource = arn
      },
      {
        Effect    = "Allow"
        Action    = ["kms:GenerateDataKey", "kms:Decrypt"]
        Resource  = var.messaging_kms_key_arn
        Condition = { StringEquals = { "kms:ViaService" = "sns.${var.region}.${data.aws_partition.current.dns_suffix}" } }
      }
    ]
  }

  consumer_permissions = {
    for name, arn in var.queue_arns : name => [
      {
        Effect = "Allow"
        Action = [
          "sqs:ReceiveMessage",
          "sqs:DeleteMessage",
          "sqs:ChangeMessageVisibility",
          "sqs:GetQueueAttributes",
          "sqs:GetQueueUrl"
        ]
        Resource = arn
      },
      {
        Effect    = "Allow"
        Action    = ["kms:Decrypt"]
        Resource  = var.messaging_kms_key_arn
        Condition = { StringEquals = { "kms:ViaService" = "sqs.${var.region}.${data.aws_partition.current.dns_suffix}" } }
      }
    ]
  }
}
