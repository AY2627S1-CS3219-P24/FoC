mock_provider "aws" {
  mock_resource "aws_iam_role" {
    defaults = { arn = "arn:aws:iam::123456789012:role/foc-test" }
  }
  mock_resource "aws_launch_template" {
    defaults = { id = "lt-0123456789abcdef0" }
  }
  mock_resource "aws_autoscaling_group" {
    defaults = { arn = "arn:aws:autoscaling:ap-southeast-1:123456789012:autoScalingGroup:00000000-0000-0000-0000-000000000001:autoScalingGroupName/foc-test-core" }
  }
  mock_data "aws_caller_identity" {
    defaults = {
      account_id = "123456789012"
    }
  }
  mock_data "aws_partition" {
    defaults = {
      partition  = "aws"
      dns_suffix = "amazonaws.com"
    }
  }
  mock_data "aws_ssm_parameter" {
    defaults = {
      value = "ami-0123456789abcdef0"
    }
  }
}

variable "start_services" {
  type    = bool
  default = false
}

run "service_credentials_and_messaging_permissions" {
  command = apply

  module {
    source = "../../modules/ecs"
  }

  variables {
    region                           = "ap-southeast-1"
    name_prefix                      = "foc-test"
    domain_name                      = "foc.example.edu"
    acme_email                       = "team@example.edu"
    mail_from                        = "noreply@mail.example.edu"
    start_services                   = var.start_services
    core_instance_type               = "t3a.medium"
    public_subnet_id                 = "subnet-public"
    compute_security_group_id        = "sg-compute"
    eip_allocation_id                = "eipalloc-test"
    service_discovery_namespace_name = "foc-test.local"
    database_host                    = "database.example"
    database_master_secret_arn       = "arn:aws:secretsmanager:ap-southeast-1:123456789012:secret:foc-test"
    database_bootstrap_sql           = "select 1"
    image_bucket_names = {
      user     = "foc-test-user"
      supplier = "foc-test-supplier"
    }
    image_bucket_policy_arns = {
      user     = "arn:aws:iam::123456789012:policy/foc-test-user-images"
      supplier = "arn:aws:iam::123456789012:policy/foc-test-supplier-images"
    }
    service_discovery_service_arns = {
      realtime = "arn:aws:servicediscovery:ap-southeast-1:123456789012:service/srv-realtime"
      user     = "arn:aws:servicediscovery:ap-southeast-1:123456789012:service/srv-user"
      supplier = "arn:aws:servicediscovery:ap-southeast-1:123456789012:service/srv-supplier"
      order    = "arn:aws:servicediscovery:ap-southeast-1:123456789012:service/srv-order"
      credit   = "arn:aws:servicediscovery:ap-southeast-1:123456789012:service/srv-credit"
    }
    topic_arns = {
      user     = "arn:aws:sns:ap-southeast-1:123456789012:foc-test-user-events"
      supplier = "arn:aws:sns:ap-southeast-1:123456789012:foc-test-supplier-events"
      order    = "arn:aws:sns:ap-southeast-1:123456789012:foc-test-order-events"
      credit   = "arn:aws:sns:ap-southeast-1:123456789012:foc-test-credit-events"
    }
    queue_arns = {
      realtime = "arn:aws:sqs:ap-southeast-1:123456789012:foc-test-realtime-events"
      user     = "arn:aws:sqs:ap-southeast-1:123456789012:foc-test-user-events"
      supplier = "arn:aws:sqs:ap-southeast-1:123456789012:foc-test-supplier-events"
      order    = "arn:aws:sqs:ap-southeast-1:123456789012:foc-test-order-events"
      credit   = "arn:aws:sqs:ap-southeast-1:123456789012:foc-test-credit-events"
    }
    queue_urls = {
      for name in ["user", "supplier", "order", "credit", "realtime"] :
      name => "https://sqs.ap-southeast-1.amazonaws.com/123456789012/foc-test-${name}-events"
    }
    messaging_kms_key_arn = "arn:aws:kms:ap-southeast-1:123456789012:key/00000000-0000-0000-0000-000000000001"
    services = {
      realtime = { port = 8084, cpu = 128, memory = 256, runtime = "node" }
      user = {
        runtime = "java"
        port    = 8080, cpu = 256, memory = 768, database = "foc_user", username = "foc_user"
        jwt     = true, mail = true, bucket_env = "FOC_USER_BUCKET"
      }
      supplier = {
        runtime = "java"
        port    = 8081, cpu = 256, memory = 640, database = "foc_supplier", username = "foc_supplier"
        jwt     = false, mail = false, bucket_env = "FOC_SUPPLIER_BUCKET"
      }
      order = {
        runtime = "java"
        port    = 8082, cpu = 512, memory = 640, database = "foc_order", username = "foc_order"
        jwt     = false, mail = false
      }
      credit = {
        runtime = "java"
        port    = 8083, cpu = 256, memory = 512, database = "foc_credit", username = "foc_credit"
        jwt     = false, mail = false
      }
    }
  }

  assert {
    condition = toset([
      for secret in local.admin_task_definition.containerDefinitions[0].secrets : secret.name
    ]) == toset(["FOC_DATABASE_PASSWORD", "INITIAL_ADMIN_EMAIL", "INITIAL_ADMIN_PASSWORD"])
    error_message = "Admin setup must receive only its database and admin secrets."
  }

  assert {
    condition     = local.admin_task_definition.taskRoleArn == "arn:${data.aws_partition.current.partition}:iam::${data.aws_caller_identity.current.account_id}:role/${aws_iam_role.task["user"].name}"
    error_message = "Admin setup must publish with the user task role."
  }

  assert {
    condition = one([
      for env in local.supplier_seed_task_definition.containerDefinitions[0].environment : env.value
      if env.name == "SPRING_PROFILES_ACTIVE"
    ]) == "prod,supplier-seed"
    error_message = "Supplier seed must run the supplier-seed profile."
  }

  assert {
    condition = toset([
      for secret in local.supplier_seed_task_definition.containerDefinitions[0].secrets : secret.name
    ]) == toset(["FOC_DATABASE_PASSWORD"])
    error_message = "Supplier seed must receive only the supplier database password."
  }

  assert {
    condition     = local.supplier_seed_task_definition.taskRoleArn == "arn:${data.aws_partition.current.partition}:iam::${data.aws_caller_identity.current.account_id}:role/${aws_iam_role.task["supplier"].name}"
    error_message = "Supplier seed must use the supplier task role."
  }

  assert {
    condition = alltrue(flatten([
      for task in aws_ecs_task_definition.database_bootstrap : [
        for secret in jsondecode(task.container_definitions)[0].secrets :
        !startswith(secret.name, "INITIAL_ADMIN_")
      ]
    ]))
    error_message = "Database creation tasks must not receive admin credentials."
  }

  assert {
    condition = alltrue(flatten([
      for policy in aws_iam_role_policy.messaging : [
        for statement in jsondecode(policy.policy).Statement : (
          statement.Resource == var.messaging_kms_key_arn
          && contains([
            "sns.ap-southeast-1.amazonaws.com",
            "sqs.ap-southeast-1.amazonaws.com"
          ], statement.Condition.StringEquals["kms:ViaService"])
        ) if anytrue([for action in statement.Action : startswith(action, "kms:")])
      ]
    ]))
    error_message = "Every KMS grant must use the messaging key through SNS or SQS."
  }

  assert {
    condition = (
      !contains(keys(aws_ecs_task_definition.database_bootstrap), "realtime")
      && length([
        for statement in jsondecode(aws_iam_role_policy.messaging["realtime"].policy).Statement : statement
        if contains(statement.Action, "sns:Publish")
      ]) == 0
    )
    error_message = "Realtime must not get database setup or SNS publishing permissions."
  }

  assert {
    condition = (
      length(jsondecode(aws_ecs_task_definition.app["realtime"].container_definitions)[0].secrets) == 0
      && toset([
        for entry in jsondecode(aws_ecs_task_definition.app["realtime"].container_definitions)[0].environment : entry.name
      ]) == toset(["AWS_REGION", "NODE_ENV", "JWKS_URL", "SQS_QUEUE_URL"])
    )
    error_message = "Realtime must receive its Node and SQS configuration without Java settings or application secrets."
  }

  assert {
    condition = alltrue([
      for name in keys(var.services) : (
        try(jsondecode(aws_ecs_task_definition.app[name].container_definitions)[0].restartPolicy.enabled, false)
        && try(jsondecode(aws_ecs_task_definition.app[name].container_definitions)[0].versionConsistency, null) == "disabled"
      )
    ])
    error_message = "All application services must enable container restarts and use disabled version consistency."
  }

  assert {
    condition = alltrue([
      for container in concat([
        jsondecode(aws_ecs_task_definition.gateway.container_definitions)[0]
        ], [for task in aws_ecs_task_definition.database_bootstrap : jsondecode(task.container_definitions)[0]]
      ) : !contains(keys(container), "restartPolicy") && !contains(keys(container), "versionConsistency")
    ])
    error_message = "Shared application policies must not change gateway or database-bootstrap container behavior."
  }

}
