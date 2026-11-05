locals {
  # The launcher inserts the image selected on the User Service before registration.
  admin_task_definition = {
    family                  = "${var.name_prefix}-admin-init"
    requiresCompatibilities = ["EC2"]
    networkMode             = "bridge"
    executionRoleArn        = aws_iam_role.bootstrap_execution.arn
    taskRoleArn             = local.task_role_arns["user"]
    volumes                 = [{ name = "jwt", host = { sourcePath = "/var/lib/runtime/jwt" } }]

    containerDefinitions = [{
      name        = "admin-init"
      essential   = true
      cpu         = var.services["user"].cpu
      memory      = var.services["user"].memory
      mountPoints = [{ sourceVolume = "jwt", containerPath = "/run/secrets", readOnly = true }]

      environment = [
        { name = "SPRING_PROFILES_ACTIVE", value = "prod,admin-init" },
        { name = "AWS_REGION", value = var.region },
        { name = "JAVA_TOOL_OPTIONS", value = "-XX:MaxRAMPercentage=60 -XX:+ExitOnOutOfMemoryError" },
        { name = "FOC_DATABASE_URL", value = "jdbc:postgresql://${var.database_host}:5432/${var.services["user"].database}?sslmode=verify-full&sslrootcert=/app/rds-ca.pem" },
        { name = "FOC_DATABASE_USERNAME", value = var.services["user"].username },
        { name = "FOC_USER_BUCKET", value = var.image_bucket_names["user"] },
        { name = "MAIL_FROM", value = var.mail_from },
        { name = "FOC_FRONTEND_URL", value = "https://${var.domain_name}" },
        { name = "INITIAL_ADMIN_NAME", value = "System Administrator" },
        { name = "EVENTS_MESSAGING_TOPIC_ARN", value = var.topic_arns["user"] }
      ]

      secrets = [
        { name = "FOC_DATABASE_PASSWORD", valueFrom = "${local.parameter_arn}-database-user" },
        { name = "INITIAL_ADMIN_EMAIL", valueFrom = "${local.parameter_arn}-initial-admin-email" },
        { name = "INITIAL_ADMIN_PASSWORD", valueFrom = "${local.parameter_arn}-initial-admin-password" }
      ]

      logConfiguration = {
        logDriver = "awslogs"
        options = {
          awslogs-group         = aws_cloudwatch_log_group.database_bootstrap.name
          awslogs-region        = var.region
          awslogs-stream-prefix = "admin-init"
        }
      }
    }]
  }
}
