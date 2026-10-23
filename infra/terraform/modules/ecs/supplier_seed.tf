locals {
  # The launcher inserts the image selected on the Supplier Service before registration.
  supplier_seed_task_definition = {
    family                  = "${var.name_prefix}-supplier-seed"
    requiresCompatibilities = ["EC2"]
    networkMode             = "bridge"
    executionRoleArn        = aws_iam_role.bootstrap_execution.arn
    taskRoleArn             = local.task_role_arns["supplier"]

    containerDefinitions = [{
      name      = "supplier-seed"
      essential = true
      cpu       = var.services["supplier"].cpu
      memory    = var.services["supplier"].memory

      environment = [
        { name = "SPRING_PROFILES_ACTIVE", value = "prod,supplier-seed" },
        { name = "AWS_REGION", value = var.region },
        { name = "JAVA_TOOL_OPTIONS", value = "-XX:MaxRAMPercentage=60 -XX:+ExitOnOutOfMemoryError" },
        { name = "FOC_DATABASE_URL", value = "jdbc:postgresql://${var.database_host}:5432/${var.services["supplier"].database}?sslmode=verify-full&sslrootcert=/app/rds-ca.pem" },
        { name = "FOC_DATABASE_USERNAME", value = var.services["supplier"].username },
        { name = "FOC_JWKS_URI", value = "https://${var.domain_name}/api/.well-known/jwks.json" },
        { name = "FOC_SUPPLIER_BUCKET", value = var.image_bucket_names["supplier"] }
      ]

      secrets = [
        { name = "FOC_DATABASE_PASSWORD", valueFrom = "${local.parameter_arn}-database-supplier" }
      ]

      logConfiguration = {
        logDriver = "awslogs"
        options = {
          awslogs-group         = aws_cloudwatch_log_group.database_bootstrap.name
          awslogs-region        = var.region
          awslogs-stream-prefix = "supplier-seed"
        }
      }
    }]
  }
}
