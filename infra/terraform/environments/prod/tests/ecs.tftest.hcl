mock_provider "aws" {
  mock_data "aws_iam_policy_document" {
    defaults = { json = jsonencode({ Version = "2012-10-17", Statement = [] }) }
  }
  mock_data "aws_availability_zones" {
    defaults = {
      names = ["ap-southeast-1a", "ap-southeast-1b", "ap-southeast-1c"]
    }
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

variable "base_services" {
  type = any
  default = {
    user = {
      port = 8080, cpu = 256, memory = 768, database = "foc_user", username = "foc_user"
      jwt  = true, mail = true, bucket_env = "FOC_USER_BUCKET"
    }
    supplier = {
      port = 8081, cpu = 256, memory = 640, database = "foc_supplier", username = "foc_supplier"
      jwt  = false, mail = false, bucket_env = "FOC_SUPPLIER_BUCKET"
    }
    order = {
      port       = 8082, cpu = 512, memory = 640, database = "foc_order", username = "foc_order"
      jwt        = false, mail = false
      ai_enabled = false
    }
    credit = {
      port = 8083, cpu = 256, memory = 512, database = "foc_credit", username = "foc_credit"
      jwt  = false, mail = false
    }
  }
}

variables {
  region                           = "ap-southeast-1"
  name_prefix                      = "foc-test"
  domain_name                      = "foc.example.edu"
  acme_email                       = "team@example.edu"
  mail_from                        = "noreply@mail.example.edu"
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
    user     = "arn:aws:sqs:ap-southeast-1:123456789012:foc-test-user-events"
    supplier = "arn:aws:sqs:ap-southeast-1:123456789012:foc-test-supplier-events"
    order    = "arn:aws:sqs:ap-southeast-1:123456789012:foc-test-order-events"
    credit   = "arn:aws:sqs:ap-southeast-1:123456789012:foc-test-credit-events"
  }
  messaging_kms_key_arn = "arn:aws:kms:ap-southeast-1:123456789012:key/00000000-0000-0000-0000-000000000001"
}

run "credentials_with_ai_disabled" {
  command = plan

  module {
    source = "../../modules/ecs"
  }

  variables {
    start_services = var.start_services
    services       = var.base_services
  }

  assert {
    condition = alltrue(flatten([
      for name, app in module.app : [
        for secret in app.container_definitions[name].container_definition.secrets : secret.name != "OPENAI_API_KEY"
      ]
    ]))
    error_message = "With AI disabled, no application container may receive the OpenAI key."
  }

  assert {
    condition = !contains(jsondecode(aws_iam_role_policy.secrets.policy).Statement[0].Resource,
    "arn:aws:ssm:ap-southeast-1:123456789012:parameter/foc-test-openai-api-key")
    error_message = "With AI disabled, the execution role must not reference the OpenAI parameter."
  }

  assert {
    condition = toset(flatten([
      for name, app in module.app : [for item in app.container_definitions[name].container_definition.environment :
        "${name}/${item.name}=${item.value}" if startswith(item.name, "FOC_ORDER_AI_")
      ]
    ])) == toset(["order/FOC_ORDER_AI_ENABLED=false"])
    error_message = "With models omitted, order must receive only the AI switch so Spring can supply both defaults."
  }

  assert {
    condition = toset([
      for secret in local.admin_task_definition.containerDefinitions[0].secrets : secret.name
    ]) == toset(["FOC_DATABASE_PASSWORD", "INITIAL_ADMIN_EMAIL", "INITIAL_ADMIN_PASSWORD"])
    error_message = "Admin setup must receive only its database and admin secrets."
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
    condition = alltrue([
      for service, policy in aws_iam_role_policy.messaging : (
        length([
          for statement in jsondecode(policy.policy).Statement : statement
          if can(statement.Condition.StringEquals["kms:ViaService"])
          && contains(["sns.ap-southeast-1.amazonaws.com", "sqs.ap-southeast-1.amazonaws.com"], statement.Condition.StringEquals["kms:ViaService"])
          && statement.Resource == "arn:aws:kms:ap-southeast-1:123456789012:key/00000000-0000-0000-0000-000000000001"
        ]) == 2
      )
    ])
    error_message = "Task roles must use the messaging key only through SNS and SQS."
  }
}

run "only_order_receives_the_enabled_ai_key" {
  command = plan

  module {
    source = "../../modules/ecs"
  }

  variables {
    start_services = var.start_services
    services = merge(var.base_services, {
      order = merge(var.base_services.order, {
        ai_enabled             = true
        ai_model               = "test-model"
        ai_transcription_model = ""
      })
    })
  }

  assert {
    condition = alltrue([
      for name, app in module.app : (
        length([for secret in app.container_definitions[name].container_definition.secrets : secret if secret.name == "OPENAI_API_KEY"])
        == (name == "order" ? 1 : 0)
        && alltrue([for secret in app.container_definitions[name].container_definition.secrets :
          secret.valueFrom == "arn:aws:ssm:ap-southeast-1:123456789012:parameter/foc-test-openai-api-key"
        if secret.name == "OPENAI_API_KEY"])
      )
    ])
    error_message = "Only order may receive the OpenAI key from the expected SSM parameter."
  }

  assert {
    condition = toset(flatten([
      for name, app in module.app : [for item in app.container_definitions[name].container_definition.environment :
        "${name}/${item.name}=${item.value}" if startswith(item.name, "FOC_ORDER_AI_")
      ]
      ])) == toset([
      "order/FOC_ORDER_AI_ENABLED=true",
      "order/FOC_ORDER_AI_MODEL=test-model",
      "order/FOC_ORDER_AI_TRANSCRIPTION_MODEL=",
    ])
    error_message = "Only order must receive the chat override and the explicit empty transcription override."
  }

  assert {
    condition = contains(jsondecode(aws_iam_role_policy.secrets.policy).Statement[0].Resource,
    "arn:aws:ssm:ap-southeast-1:123456789012:parameter/foc-test-openai-api-key")
    error_message = "With AI enabled, the execution role must be able to read the expected OpenAI parameter."
  }
}
