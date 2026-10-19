locals {
  repositories     = setunion(toset(keys(var.services)), toset(["gateway"]))
  parameter_prefix = var.name_prefix
  parameter_arn    = "arn:${data.aws_partition.current.partition}:ssm:${var.region}:${data.aws_caller_identity.current.account_id}:parameter/${local.parameter_prefix}"
  eip_arn          = "arn:${data.aws_partition.current.partition}:ec2:${var.region}:${data.aws_caller_identity.current.account_id}:elastic-ip/${var.eip_allocation_id}"
  tags             = { Project = "FoC", Environment = var.name_prefix }
}

data "aws_caller_identity" "current" {}

data "aws_partition" "current" {}

data "aws_ssm_parameter" "ecs_ami" {
  name = "/aws/service/ecs/optimized-ami/amazon-linux-2023/recommended/image_id"
}

resource "aws_ecs_cluster" "app" {
  name = var.name_prefix
  setting {
    name  = "containerInsights"
    value = "disabled"
  }
}

resource "aws_ecs_capacity_provider" "host" {
  for_each = toset(["core", "burst"])
  name     = "${var.name_prefix}-${each.key}"
  auto_scaling_group_provider {
    auto_scaling_group_arn         = aws_autoscaling_group.host[each.key].arn
    managed_termination_protection = each.key == "burst" ? "ENABLED" : "DISABLED"
    managed_draining               = "ENABLED"
    dynamic "managed_scaling" {
      for_each = each.key == "burst" ? [1] : []
      content {
        status                    = "ENABLED"
        target_capacity           = 100
        minimum_scaling_step_size = 1
        maximum_scaling_step_size = 1
        instance_warmup_period    = 120
      }
    }
  }
}

resource "aws_ecs_cluster_capacity_providers" "app" {
  cluster_name       = aws_ecs_cluster.app.name
  capacity_providers = [for provider in aws_ecs_capacity_provider.host : provider.name]
  default_capacity_provider_strategy {
    capacity_provider = aws_ecs_capacity_provider.host["core"].name
    weight            = 1
  }
}

resource "aws_cloudwatch_log_group" "app" {
  for_each          = local.repositories
  name              = "/ecs/${var.name_prefix}/${each.key}"
  retention_in_days = 7
}

resource "aws_cloudwatch_log_group" "database_bootstrap" {
  name              = "/ecs/${var.name_prefix}/database-bootstrap"
  retention_in_days = 7
}

resource "aws_ecs_task_definition" "database_bootstrap" {
  for_each                 = var.services
  family                   = "${var.name_prefix}-database-bootstrap-${each.key}"
  requires_compatibilities = ["EC2"]
  network_mode             = "bridge"
  execution_role_arn       = aws_iam_role.bootstrap_execution.arn
  container_definitions = jsonencode([{
    name       = "database-bootstrap"
    image      = "postgres:18-alpine"
    essential  = true
    user       = "postgres"
    cpu        = 128
    memory     = 128
    entryPoint = ["/bin/sh", "-ec"]
    command = [join("\n", [
      "wget -q -O /tmp/rds-ca.pem https://truststore.pki.rds.amazonaws.com/${var.region}/${var.region}-bundle.pem",
      "exec psql -X --set=ON_ERROR_STOP=on --set=VERBOSITY=terse <<'SQL'",
      var.database_bootstrap_sql,
      "SQL"
    ])]
    environment = [
      { name = "PGHOST", value = var.database_host },
      { name = "PGDATABASE", value = "foc" },
      { name = "PGUSER", value = "foc_admin" },
      { name = "PGSSLMODE", value = "verify-full" },
      { name = "PGSSLROOTCERT", value = "/tmp/rds-ca.pem" },
      { name = "PGCONNECT_TIMEOUT", value = "10" },
      { name = "FOC_DATABASE_NAME", value = each.value.database },
      { name = "FOC_DATABASE_USERNAME", value = each.value.username }
    ]
    secrets = [
      { name = "PGPASSWORD", valueFrom = "${var.database_master_secret_arn}:password::" },
      { name = "FOC_DATABASE_PASSWORD", valueFrom = "${local.parameter_arn}-database-${each.key}" }
    ]
    logConfiguration = {
      logDriver = "awslogs"
      options = {
        awslogs-group         = aws_cloudwatch_log_group.database_bootstrap.name
        awslogs-region        = var.region
        awslogs-stream-prefix = "bootstrap"
      }
    }
  }])
}

resource "aws_ecr_repository" "app" {
  for_each             = local.repositories
  name                 = "${var.name_prefix}/${each.key}"
  image_tag_mutability = "IMMUTABLE"
  image_scanning_configuration { scan_on_push = true }
}

resource "aws_ecr_lifecycle_policy" "app" {
  for_each   = aws_ecr_repository.app
  repository = each.value.name
  policy = jsonencode({
    rules = [{
      rulePriority = 1
      description  = "Retain two releases for rollback"
      selection = {
        tagStatus   = "any"
        countType   = "imageCountMoreThan"
        countNumber = 2
      }
      action = { type = "expire" }
    }]
  })
}

resource "aws_s3_bucket" "configuration" {
  bucket_prefix = "${var.name_prefix}-configuration-"
  tags          = local.tags
}

resource "aws_s3_bucket_public_access_block" "configuration" {
  bucket                  = aws_s3_bucket.configuration.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_server_side_encryption_configuration" "configuration" {
  bucket = aws_s3_bucket.configuration.id
  rule {
    apply_server_side_encryption_by_default { sse_algorithm = "AES256" }
  }
}

resource "aws_s3_bucket_policy" "configuration" {
  bucket = aws_s3_bucket.configuration.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Deny"
      Principal = "*"
      Action    = "s3:*"
      Resource  = [aws_s3_bucket.configuration.arn, "${aws_s3_bucket.configuration.arn}/*"]
      Condition = { Bool = { "aws:SecureTransport" = "false" } }
    }]
  })
}

resource "aws_launch_template" "host" {
  for_each      = toset(["core", "burst"])
  name_prefix   = "${var.name_prefix}-${each.key}-"
  image_id      = data.aws_ssm_parameter.ecs_ami.value
  instance_type = each.key == "core" ? var.core_instance_type : "t3a.small"
  iam_instance_profile { name = aws_iam_instance_profile.host[each.key].name }
  vpc_security_group_ids = [var.compute_security_group_id]
  metadata_options {
    http_tokens                 = "required"
    http_put_response_hop_limit = 1
  }
  credit_specification { cpu_credits = "standard" }
  block_device_mappings {
    device_name = "/dev/xvda"
    ebs {
      volume_type = "gp3"
      # Match the ECS-optimized AL2023 AMI root volume.
      volume_size           = 30
      encrypted             = true
      delete_on_termination = true
    }
  }
  user_data = base64encode(templatefile("${path.module}/templates/user_data.sh.tftpl", {
    cluster = aws_ecs_cluster.app.name
    pool    = each.key
    prepare_core = each.key == "core" ? templatefile("${path.module}/templates/prepare_core.sh.tftpl", {
      region               = var.region
      parameter_prefix     = local.parameter_prefix
      eip_allocation       = var.eip_allocation_id
      configuration_bucket = aws_s3_bucket.configuration.id
    }) : ""
    renew_certificate = each.key == "core" ? templatefile("${path.module}/templates/renew_certificate.sh.tftpl", {
      region               = var.region
      domain               = var.domain_name
      acme_email           = var.acme_email
      configuration_bucket = aws_s3_bucket.configuration.id
    }) : ""
  }))
  tag_specifications {
    resource_type = "instance"
    tags          = merge(local.tags, { Name = "${var.name_prefix}-${each.key}" })
  }
}

resource "aws_autoscaling_group" "host" {
  for_each              = toset(["core", "burst"])
  name                  = "${var.name_prefix}-${each.key}"
  min_size              = each.key == "core" ? 1 : 0
  desired_capacity      = each.key == "core" ? 1 : 0
  max_size              = each.key == "core" ? 1 : 2
  vpc_zone_identifier   = [var.public_subnet_id]
  protect_from_scale_in = each.key == "burst"
  launch_template {
    id      = aws_launch_template.host[each.key].id
    version = aws_launch_template.host[each.key].latest_version
  }
  instance_refresh {
    strategy = "Rolling"
    preferences {
      min_healthy_percentage = 0
      max_healthy_percentage = 100
    }
  }
  tag {
    key                 = "AmazonECSManaged"
    value               = "true"
    propagate_at_launch = true
  }
  depends_on = [
    aws_iam_role_policy_attachment.host_ecs,
    aws_iam_role_policy_attachment.host_ssm,
    aws_iam_role_policy.core_bootstrap
  ]
  lifecycle { ignore_changes = [desired_capacity] }
}
