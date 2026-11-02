# Container limits leave room for the ECS agent and OS on the core host.
# After an OOM, increase both the host size and the affected container limit.

module "app" {
  source   = "terraform-aws-modules/ecs/aws//modules/service"
  version  = "7.6.1"
  for_each = var.services

  name                     = each.key
  family                   = "${var.name_prefix}-${each.key}"
  requires_compatibilities = ["EC2"]
  network_mode             = "bridge"
  cpu                      = null
  memory                   = null
  runtime_platform = {
    cpu_architecture        = "X86_64"
    operating_system_family = "LINUX"
  }

  skip_destroy = true
  track_latest = false

  create_service            = false
  create_iam_role           = false
  create_security_group     = false
  enable_autoscaling        = false
  create_tasks_iam_role     = false
  tasks_iam_role_arn        = aws_iam_role.task[each.key].arn
  create_task_exec_iam_role = false
  create_task_exec_policy   = false
  task_exec_iam_role_arn    = aws_iam_role.execution.arn

  volume = each.value.jwt ? { jwt = { host_path = "/var/lib/runtime/jwt" } } : {}

  container_definitions = {
    (each.key) = {
      image                  = "${aws_ecr_repository.app[each.key].repository_url}:bootstrap"
      essential              = true
      cpu                    = each.value.cpu
      memory                 = each.value.memory
      readonlyRootFilesystem = false
      startTimeout           = 30
      stopTimeout            = 30
      portMappings           = [{ containerPort = each.value.port, hostPort = 0, protocol = "tcp" }]
      mountPoints            = each.value.jwt ? [{ sourceVolume = "jwt", containerPath = "/run/secrets", readOnly = true }] : []
      environment = concat([
        { name = "SPRING_PROFILES_ACTIVE", value = "prod" },
        { name = "AWS_REGION", value = var.region },
        { name = "JAVA_TOOL_OPTIONS", value = "-XX:InitialRAMPercentage=20 -XX:MaxRAMPercentage=60 -XX:+ExitOnOutOfMemoryError" },
        { name = "MANAGEMENT_OTLP_METRICS_EXPORT_ENABLED", value = "true" },
        { name = "MANAGEMENT_OTLP_METRICS_EXPORT_STEP", value = "1m" },
        { name = "OTEL_SERVICE_NAME", value = each.key },
        { name = "FOC_DATABASE_URL", value = "jdbc:postgresql://${var.database_host}:5432/${each.value.database}?sslmode=verify-full&sslrootcert=/app/rds-ca.pem" },
        { name = "FOC_DATABASE_USERNAME", value = each.value.username },
        { name = "FOC_JWKS_URI", value = "https://${var.domain_name}/api/.well-known/jwks.json" },
        { name = "FOC_FRONTEND_URL", value = "https://${var.domain_name}" },
        { name = "EVENTS_MESSAGING_TOPIC_ARN", value = var.topic_arns[each.key] },
        { name = "EVENTS_MESSAGING_QUEUE_NAME", value = "${var.name_prefix}-${each.key}-events" }
        ], each.value.bucket_env == null ? [] : [
        { name = each.value.bucket_env, value = var.image_bucket_names[each.key] }
        ], each.value.mail ? [
        { name = "MAIL_FROM", value = var.mail_from }
      ] : [])
      secrets = concat(
        [
          { name = "FOC_DATABASE_PASSWORD", valueFrom = "${local.parameter_arn}-database-${each.key}" },
          { name = "MANAGEMENT_OTLP_METRICS_EXPORT_URL", valueFrom = "${local.parameter_arn}-grafana-otlp-url" },
          { name = "MANAGEMENT_OTLP_METRICS_EXPORT_HEADERS_AUTHORIZATION", valueFrom = "${local.parameter_arn}-grafana-otlp-authorization" }
        ],
        each.value.mail ? [
          { name = "MAIL_USERNAME", valueFrom = "${local.parameter_arn}-mail-username" },
          { name = "MAIL_PASSWORD", valueFrom = "${local.parameter_arn}-mail-password" }
        ] : []
      )
      healthCheck = {
        command     = ["CMD-SHELL", "curl -fsS http://localhost:${each.value.port}/api/actuator/health || exit 1"]
        interval    = 30
        timeout     = 5
        retries     = 3
        startPeriod = 120
      }

      create_cloudwatch_log_group = false
      enable_cloudwatch_logging   = false
      logConfiguration = {
        logDriver = "awslogs"
        options = {
          awslogs-group         = aws_cloudwatch_log_group.app[each.key].name
          awslogs-region        = var.region
          awslogs-stream-prefix = "ecs"
          mode                  = "non-blocking"
          max-buffer-size       = "1m"
        }
      }
    }
  }
}

resource "aws_ecs_service" "app" {
  for_each                           = var.services
  name                               = each.key
  cluster                            = aws_ecs_cluster.app.id
  task_definition                    = module.app[each.key].task_definition_arn
  desired_count                      = var.start_services ? 1 : 0
  deployment_minimum_healthy_percent = 0
  deployment_maximum_percent         = 100

  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  capacity_provider_strategy {
    capacity_provider = aws_ecs_capacity_provider.host.name
    weight            = 1
  }

  service_registries {
    registry_arn   = var.service_discovery_service_arns[each.key]
    container_name = each.key
    container_port = each.value.port
  }

  depends_on = [aws_ecs_cluster_capacity_providers.app, aws_iam_role_policy.secrets]

  lifecycle { ignore_changes = [task_definition] }
}

resource "aws_ecs_task_definition" "gateway" {
  # Keep previous revisions available for ECS circuit-breaker rollback.
  skip_destroy             = true
  family                   = "${var.name_prefix}-gateway"
  requires_compatibilities = ["EC2"]
  network_mode             = "bridge"
  execution_role_arn       = aws_iam_role.execution.arn

  volume {
    name      = "tls"
    host_path = "/var/lib/runtime/tls"
  }

  volume {
    name      = "acme"
    host_path = "/var/lib/runtime/acme"
  }

  container_definitions = jsonencode([{
    name         = "gateway"
    image        = "${aws_ecr_repository.app["gateway"].repository_url}:bootstrap"
    essential    = true
    cpu          = 128
    memory       = 128
    portMappings = [for port in [80, 443] : { containerPort = port, hostPort = port, protocol = "tcp" }],
    mountPoints = [
      { sourceVolume = "tls", containerPath = "/etc/nginx/certs", readOnly = false },
      { sourceVolume = "acme", containerPath = "/var/www/acme", readOnly = true }
    ],
    environment = [
      { name = "FOC_DISCOVERY_NAMESPACE", value = var.service_discovery_namespace_name },
      { name = "FOC_USER_UPSTREAM", value = "${var.service_discovery_namespace_name} service=user" },
      { name = "FOC_SUPPLIER_UPSTREAM", value = "${var.service_discovery_namespace_name} service=supplier" },
      { name = "FOC_ORDER_UPSTREAM", value = "${var.service_discovery_namespace_name} service=order" },
      { name = "FOC_CREDIT_UPSTREAM", value = "${var.service_discovery_namespace_name} service=credit" }
    ],
    healthCheck = {
      command     = ["CMD-SHELL", "curl -fsS http://localhost/healthz >/dev/null || exit 1"]
      interval    = 30
      timeout     = 5
      retries     = 3
      startPeriod = 30
    }

    logConfiguration = {
      logDriver = "awslogs"
      options = {
        awslogs-group         = aws_cloudwatch_log_group.app["gateway"].name
        awslogs-region        = var.region
        awslogs-stream-prefix = "ecs"
        mode                  = "non-blocking"
        max-buffer-size       = "1m"
      }
    }
  }])
}

resource "aws_ecs_service" "gateway" {
  name                               = "gateway"
  cluster                            = aws_ecs_cluster.app.id
  task_definition                    = aws_ecs_task_definition.gateway.arn
  desired_count                      = var.start_services ? 1 : 0
  deployment_minimum_healthy_percent = 0
  deployment_maximum_percent         = 100

  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  capacity_provider_strategy {
    capacity_provider = aws_ecs_capacity_provider.host.name
    weight            = 1
  }

  depends_on = [aws_ecs_cluster_capacity_providers.app]
  lifecycle { ignore_changes = [task_definition] }
}
