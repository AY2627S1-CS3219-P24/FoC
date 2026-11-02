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
      node = merge(local.runtime_settings.node.environment, { PORT = tostring(service.port) })
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

  service_environment = {
    for name, service in var.services : name => merge(
      { AWS_REGION = var.region },
      local.runtime_environment[name],
      lookup(local.database_environment, name, {}),
      lookup(local.publisher_environment, name, {}),
      lookup(local.consumer_environment, name, {}),
      lookup(local.bucket_environment, name, {}),
      lookup(local.mail_environment, name, {})
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

  service_secrets = {
    for name, service in var.services : name => merge(
      local.runtime_settings[service.runtime].secrets,
      lookup(local.database_secrets, name, {}),
      lookup(local.mail_secrets, name, {})
    )
  }
}
