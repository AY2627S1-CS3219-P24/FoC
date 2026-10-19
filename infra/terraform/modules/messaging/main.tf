resource "aws_sns_topic" "events" {
  for_each = var.service_names
  name     = "${var.name_prefix}-${each.key}-events"
}

resource "aws_sqs_queue" "dead_letter" {
  for_each                  = var.service_names
  name                      = "${var.name_prefix}-${each.key}-dlq"
  message_retention_seconds = 1209600
  sqs_managed_sse_enabled   = true
}

resource "aws_sqs_queue" "events" {
  for_each                   = var.service_names
  name                       = "${var.name_prefix}-${each.key}-events"
  message_retention_seconds  = 345600
  visibility_timeout_seconds = 60
  receive_wait_time_seconds  = 20
  sqs_managed_sse_enabled    = true
  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.dead_letter[each.key].arn
    maxReceiveCount     = 5
  })
}

resource "aws_sqs_queue_redrive_allow_policy" "events" {
  for_each  = var.service_names
  queue_url = aws_sqs_queue.dead_letter[each.key].url
  redrive_allow_policy = jsonencode({
    redrivePermission = "byQueue"
    sourceQueueArns   = [aws_sqs_queue.events[each.key].arn]
  })
}

resource "aws_sqs_queue_policy" "sns" {
  for_each = {
    for consumer in var.service_names : consumer => consumer
    if anytrue([for route in values(var.event_subscriptions) : route.consumer == consumer])
  }
  queue_url = aws_sqs_queue.events[each.key].url
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "sns.amazonaws.com" }
      Action    = "sqs:SendMessage"
      Resource  = aws_sqs_queue.events[each.key].arn
      Condition = {
        ArnEquals = {
          "aws:SourceArn" = distinct([
            for route in values(var.event_subscriptions) : aws_sns_topic.events[route.publisher].arn
            if route.consumer == each.key
          ])
        }
      }
    }]
  })
}

resource "aws_sns_topic_subscription" "events" {
  for_each             = var.event_subscriptions
  topic_arn            = aws_sns_topic.events[each.value.publisher].arn
  protocol             = "sqs"
  endpoint             = aws_sqs_queue.events[each.value.consumer].arn
  raw_message_delivery = true
  filter_policy_scope  = "MessageAttributes"
  filter_policy        = jsonencode({ eventType = each.value.event_types })
  depends_on           = [aws_sqs_queue_policy.sns]
}

check "event_subscription_services" {
  assert {
    condition = alltrue([
      for route in values(var.event_subscriptions) :
      contains(var.service_names, route.publisher) && contains(var.service_names, route.consumer)
    ])
    error_message = "Event routes must name configured services."
  }
}
