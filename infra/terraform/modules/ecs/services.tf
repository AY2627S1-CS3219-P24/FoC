locals {
  runtime_settings = {
    java = {
      environment = {
        SPRING_PROFILES_ACTIVE                 = "prod"
        JAVA_TOOL_OPTIONS                      = "-XX:InitialRAMPercentage=20 -XX:MaxRAMPercentage=60 -XX:+ExitOnOutOfMemoryError"
        MANAGEMENT_OTLP_METRICS_EXPORT_ENABLED = "true"
        MANAGEMENT_OTLP_METRICS_EXPORT_STEP    = "1m"
        FOC_JWKS_URI                           = "https://${var.domain_name}/api/.well-known/jwks.json"
        FOC_FRONTEND_URL                       = "https://${var.domain_name}"
      }
      secrets = {
        MANAGEMENT_OTLP_METRICS_EXPORT_URL                   = "${local.parameter_arn}-grafana-otlp-url"
        MANAGEMENT_OTLP_METRICS_EXPORT_HEADERS_AUTHORIZATION = "${local.parameter_arn}-grafana-otlp-authorization"
      }
      health_path = "/api/actuator/health"
    }
    node = {
      environment = {
        NODE_ENV = "production"
        JWKS_URL = "https://${var.domain_name}/api/.well-known/jwks.json"
      }
      secrets     = {}
      health_path = "/health"
    }
  }

  runtime_environment = {
    for name, service in var.services : name => {
      java = merge(local.runtime_settings.java.environment, { OTEL_SERVICE_NAME = name })
      node = local.runtime_settings.node.environment
    }[service.runtime]
  }

  database_environment = {
    for name, service in local.database_services : name => {
      FOC_DATABASE_URL      = "jdbc:postgresql://${var.database_host}:5432/${service.database}?sslmode=verify-full&sslrootcert=/app/rds-ca.pem"
      FOC_DATABASE_USERNAME = service.username
    }
  }

  publisher_environment = {
    for name, arn in var.topic_arns : name => { EVENTS_MESSAGING_TOPIC_ARN = arn }
  }

  consumer_environment = {
    for name, url in var.queue_urls : name => {
      java = { EVENTS_MESSAGING_QUEUE_NAME = "${var.name_prefix}-${name}-events" }
      node = { SQS_QUEUE_URL = url }
    }[var.services[name].runtime]
  }

  bucket_environment = {
    for name, service in var.services : name => {
      (service.bucket_env) = var.image_bucket_names[name]
    } if service.bucket_env != null
  }

  mail_environment = {
    for name, service in var.services : name => { MAIL_FROM = var.mail_from } if service.mail
  }

  capability_environments = [
    local.database_environment,
    local.publisher_environment,
    local.consumer_environment,
    local.bucket_environment,
    local.mail_environment
  ]

  service_environment = {
    for name in keys(var.services) : name => merge(
      { AWS_REGION = var.region },
      local.runtime_environment[name],
      [for environment in local.capability_environments : environment[name] if contains(keys(environment), name)]...
    )
  }

  database_secrets = {
    for name in keys(local.database_services) : name => {
      FOC_DATABASE_PASSWORD = "${local.parameter_arn}-database-${name}"
    }
  }

  mail_secrets = {
    for name, service in var.services : name => {
      MAIL_USERNAME = "${local.parameter_arn}-mail-username"
      MAIL_PASSWORD = "${local.parameter_arn}-mail-password"
    } if service.mail
  }

  capability_secrets = [local.database_secrets, local.mail_secrets]

  service_secrets = {
    for name, service in var.services : name => merge(
      local.runtime_settings[service.runtime].secrets,
      [for secrets in local.capability_secrets : secrets[name] if contains(keys(secrets), name)]...
    )
  }
}

# Container limits leave room for the ECS agent and OS on the core host.
# After an OOM, increase both the host size and the affected container limit.

resource "aws_ecs_task_definition" "app" {
  for_each = var.services

  family                   = "${var.name_prefix}-${each.key}"
  requires_compatibilities = ["EC2"]
  network_mode             = "bridge"
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = aws_iam_role.task[each.key].arn
  skip_destroy             = true

  runtime_platform {
    cpu_architecture        = "X86_64"
    operating_system_family = "LINUX"
  }

  dynamic "volume" {
    for_each = each.value.jwt ? ["jwt"] : []
    content {
      name      = volume.value
      host_path = "/var/lib/runtime/jwt"
    }
  }

  container_definitions = jsonencode([{
    name                   = each.key
    image                  = "${aws_ecr_repository.app[each.key].repository_url}:bootstrap"
    essential              = true
    cpu                    = each.value.cpu
    memory                 = each.value.memory
    readonlyRootFilesystem = false
    restartPolicy          = { enabled = true }
    versionConsistency     = "disabled"
    startTimeout           = 30
    stopTimeout            = 30
    portMappings           = [{ containerPort = each.value.port, hostPort = 0, protocol = "tcp" }]
    mountPoints            = each.value.jwt ? [{ sourceVolume = "jwt", containerPath = "/run/secrets", readOnly = true }] : []
    environment            = [for name, value in local.service_environment[each.key] : { name = name, value = value }]
    secrets                = [for name, arn in local.service_secrets[each.key] : { name = name, valueFrom = arn }]
    healthCheck = {
      command     = ["CMD-SHELL", "curl -fsS http://localhost:${each.value.port}${local.runtime_settings[each.value.runtime].health_path} || exit 1"]
      interval    = 30
      timeout     = 5
      retries     = 3
      startPeriod = 120
    }
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
  }])

}

resource "aws_ecs_service" "app" {
  for_each                           = var.services
  name                               = each.key
  cluster                            = aws_ecs_cluster.app.id
  task_definition                    = aws_ecs_task_definition.app[each.key].arn
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

  depends_on = [aws_ecs_cluster_capacity_providers.app, aws_iam_role_policy.secrets, aws_iam_role_policy.messaging]

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
      { name = "FOC_USER_UPSTREAM", value = "${var.service_discovery_namespace_name} service=user" },
      { name = "FOC_SUPPLIER_UPSTREAM", value = "${var.service_discovery_namespace_name} service=supplier" },
      { name = "FOC_ORDER_UPSTREAM", value = "${var.service_discovery_namespace_name} service=order" },
      { name = "FOC_CREDIT_UPSTREAM", value = "${var.service_discovery_namespace_name} service=credit" },
      { name = "FOC_REALTIME_UPSTREAM", value = "${var.service_discovery_namespace_name} service=realtime" }
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
