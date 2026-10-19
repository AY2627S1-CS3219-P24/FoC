locals {
  ecs_trust = jsonencode({ Version = "2012-10-17", Statement = [{ Effect = "Allow", Principal = { Service = "ecs-tasks.amazonaws.com" }, Action = "sts:AssumeRole" }] })
}
resource "aws_iam_role" "execution" {
  name               = "${var.name_prefix}-execution"
  assume_role_policy = local.ecs_trust
}
resource "aws_iam_role_policy_attachment" "execution" {
  role       = aws_iam_role.execution.name
  policy_arn = "arn:${data.aws_partition.current.partition}:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}
resource "aws_iam_role_policy" "secrets" {
  role   = aws_iam_role.execution.id
  policy = jsonencode({ Version = "2012-10-17", Statement = [{ Effect = "Allow", Action = ["ssm:GetParameters"], Resource = ["${local.parameter_arn}-database-*", "${local.parameter_arn}-mail-*", "${local.parameter_arn}-grafana-*"] }] })
}
resource "aws_iam_role" "task" {
  for_each           = toset(keys(var.services))
  name               = "${var.name_prefix}-${each.key}-task"
  assume_role_policy = local.ecs_trust
}
resource "aws_iam_role_policy_attachment" "images" {
  for_each   = var.image_bucket_policy_arns
  role       = aws_iam_role.task[each.key].name
  policy_arn = each.value
}
resource "aws_iam_role_policy" "messaging" {
  for_each = var.services
  role     = aws_iam_role.task[each.key].id
  policy = jsonencode({ Version = "2012-10-17", Statement = [
    { Effect = "Allow", Action = ["sns:Publish"], Resource = var.topic_arns[each.key] },
    { Effect = "Allow", Action = ["sqs:ReceiveMessage", "sqs:DeleteMessage", "sqs:ChangeMessageVisibility", "sqs:GetQueueAttributes", "sqs:GetQueueUrl"], Resource = var.queue_arns[each.key] }
  ] })
}
resource "aws_iam_role" "host" {
  for_each           = toset(["core", "burst"])
  name               = "${var.name_prefix}-${each.key}-host"
  assume_role_policy = jsonencode({ Version = "2012-10-17", Statement = [{ Effect = "Allow", Principal = { Service = "ec2.amazonaws.com" }, Action = "sts:AssumeRole" }] })
}
resource "aws_iam_role_policy_attachment" "host_ecs" {
  for_each   = aws_iam_role.host
  role       = each.value.name
  policy_arn = "arn:${data.aws_partition.current.partition}:iam::aws:policy/service-role/AmazonEC2ContainerServiceforEC2Role"
}
resource "aws_iam_role_policy_attachment" "host_ssm" {
  for_each   = aws_iam_role.host
  role       = each.value.name
  policy_arn = "arn:${data.aws_partition.current.partition}:iam::aws:policy/AmazonSSMManagedInstanceCore"
}
resource "aws_iam_role_policy" "core_bootstrap" {
  role = aws_iam_role.host["core"].id
  policy = jsonencode({ Version = "2012-10-17", Statement = [
    { Effect = "Allow", Action = ["ssm:GetParameter"], Resource = ["${local.parameter_arn}-jwt-*"] },
    { Effect = "Allow", Action = ["s3:GetObject", "s3:PutObject"], Resource = "${aws_s3_bucket.configuration.arn}/tls.tar.gz" },
    { Effect = "Allow", Action = ["s3:ListBucket"], Resource = aws_s3_bucket.configuration.arn, Condition = { StringEquals = { "s3:prefix" = "tls.tar.gz" } } },
    { Effect = "Allow", Action = ["ec2:AssociateAddress"], Resource = [local.eip_arn, "arn:${data.aws_partition.current.partition}:ec2:${var.region}:${data.aws_caller_identity.current.account_id}:instance/*"] }
  ] })
}
resource "aws_iam_instance_profile" "host" {
  for_each = aws_iam_role.host
  name     = "${var.name_prefix}-${each.key}"
  role     = each.value.name
}

resource "aws_iam_openid_connect_provider" "github" {
  count          = var.github_oidc_provider_arn == "" ? 1 : 0
  url            = "https://token.actions.githubusercontent.com"
  client_id_list = ["sts.amazonaws.com"]
}
resource "aws_iam_role" "bootstrap_execution" {
  name               = "${var.name_prefix}-bootstrap-execution"
  assume_role_policy = local.ecs_trust
}

resource "aws_iam_role_policy_attachment" "bootstrap_execution" {
  role       = aws_iam_role.bootstrap_execution.name
  policy_arn = "arn:${data.aws_partition.current.partition}:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

resource "aws_iam_role_policy" "bootstrap_secrets" {
  role = aws_iam_role.bootstrap_execution.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect   = "Allow"
        Action   = ["secretsmanager:GetSecretValue"]
        Resource = var.database_master_secret_arn
      },
      {
        Effect   = "Allow"
        Action   = ["ssm:GetParameters"]
        Resource = [for service in keys(var.services) : "${local.parameter_arn}-database-${service}"]
      }
    ]
  })
}

resource "aws_iam_role" "grafana_cloudwatch" {
  count              = var.grafana_cloudwatch_account_id == "" ? 0 : 1
  name               = "${var.name_prefix}-grafana-cloudwatch"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { AWS = "arn:${data.aws_partition.current.partition}:iam::${var.grafana_cloudwatch_account_id}:root" }
      Action    = "sts:AssumeRole"
      Condition = { StringEquals = { "sts:ExternalId" = var.grafana_cloudwatch_external_id } }
    }]
  })
}

resource "aws_iam_role_policy" "grafana_cloudwatch" {
  count = length(aws_iam_role.grafana_cloudwatch)
  role  = aws_iam_role.grafana_cloudwatch[0].id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid      = "ReadCloudWatchMetrics"
        Effect   = "Allow"
        Action   = ["cloudwatch:GetMetricData", "cloudwatch:GetMetricStatistics", "cloudwatch:ListMetrics"]
        Resource = "*"
      },
      {
        Sid      = "DescribeLogGroupsAndStopQueries"
        Effect   = "Allow"
        Action   = ["logs:DescribeLogGroups", "logs:StopQuery"]
        Resource = "*"
      },
      {
        Sid    = "ReadApplicationLogs"
        Effect = "Allow"
        Action = ["logs:DescribeLogStreams", "logs:GetQueryResults", "logs:FilterLogEvents", "logs:GetLogEvents", "logs:StartQuery", "logs:GetLogGroupFields"]
        Resource = [
          "arn:${data.aws_partition.current.partition}:logs:${var.region}:${data.aws_caller_identity.current.account_id}:log-group:/ecs/${var.name_prefix}/*",
          "arn:${data.aws_partition.current.partition}:logs:${var.region}:${data.aws_caller_identity.current.account_id}:log-group:/ecs/${var.name_prefix}/*:log-stream:*"
        ]
      }
    ]
  })
}
