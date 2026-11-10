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
}

variable "start_services" {
  type    = bool
  default = false
}

variable "base_services" {
  type = any
  default = {
    realtime = { port = 8084, cpu = 128, memory = 256, runtime = "node" }
    user = {
      runtime = "java"
      port    = 8080, cpu = 256, memory = 512, database = "foc_user", username = "foc_user"
      jwt     = true, mail = true, bucket_env = "FOC_USER_BUCKET"
    }
    supplier = {
      runtime = "java"
      port    = 8081, cpu = 256, memory = 512, database = "foc_supplier", username = "foc_supplier"
      jwt     = false, mail = false, bucket_env = "FOC_SUPPLIER_BUCKET"
    }
    order = {
      runtime = "java"
      port    = 8082, cpu = 512, memory = 512, database = "foc_order", username = "foc_order"
      jwt     = false, mail = false, bucket_env = "FOC_ORDER_BUCKET"
    }
    credit = {
      runtime = "java"
      port    = 8083, cpu = 256, memory = 512, database = "foc_credit", username = "foc_credit"
      jwt     = false, mail = false
    }
  }
}

variables {
  region                        = "ap-southeast-1"
  name_prefix                   = "foc-test"
  domain_name                   = "foc.example.edu"
  acme_email                    = "team@example.edu"
  mail_from                     = "noreply@mail.example.edu"
  core_ami_id                   = "ami-0123456789abcdef0"
  core_instance_type            = "t3a.medium"
  public_subnet_id              = "subnet-public"
  compute_security_group_id     = "sg-compute"
  eip_allocation_id             = "eipalloc-test"
  service_connect_namespace_arn = "arn:aws:servicediscovery:ap-southeast-1:123456789012:namespace/ns-test"
  database_host                 = "database.example"
  database_master_secret_arn    = "arn:aws:secretsmanager:ap-southeast-1:123456789012:secret:foc-test"
  database_bootstrap_sql        = "select 1"
  image_bucket_names = {
    user     = "foc-test-user"
    supplier = "foc-test-supplier"
    order    = "foc-test-order"
  }
  image_bucket_policy_arns = {
    user     = "arn:aws:iam::123456789012:policy/foc-test-user-images"
    supplier = "arn:aws:iam::123456789012:policy/foc-test-supplier-images"
    order    = "arn:aws:iam::123456789012:policy/foc-test-order-images"
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
}

run "service_credentials_and_messaging_permissions" {
  command = apply

  module {
    source = "../../modules/ecs"
  }

  variables {
    start_services = var.start_services
    services       = var.base_services
  }

  assert {
    condition     = aws_launch_template.host.image_id == var.core_ami_id
    error_message = "The host must use the explicitly pinned AMI."
  }

  assert {
    condition = (
      strcontains(base64decode(aws_launch_template.host.user_data), "OnUnitActiveSec=12h")
      && strcontains(base64decode(aws_launch_template.host.user_data), "--deploy-hook /usr/local/bin/deploy-certificate")
      && strcontains(base64decode(aws_launch_template.host.user_data), "RestartSec=5min")
    )
    error_message = "Certificate checks must use the deploy hook, run twice daily and retry bootstrap failures."
  }

  assert {
    condition = alltrue(flatten([
      for name, task in aws_ecs_task_definition.app : [
        for secret in jsondecode(task.container_definitions)[0].secrets : secret.name != "OPENAI_API_KEY"
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
      for name, task in aws_ecs_task_definition.app : [for item in jsondecode(task.container_definitions)[0].environment :
        "${name}/${item.name}=${item.value}" if startswith(item.name, "FOC_ORDER_AI_")
      ]
    ])) == toset(["order/FOC_ORDER_AI_ENABLED=false"])
    error_message = "With models omitted, order must receive only the AI switch so Spring can supply both defaults."
  }

  assert {
    condition = toset(flatten([
      for name, task in aws_ecs_task_definition.app : [for item in jsondecode(task.container_definitions)[0].environment :
        "${name}/${item.name}=${item.value}" if endswith(item.name, "_SERVICE_URL")
      ]
      ])) == toset([
      "order/FOC_CREDIT_SERVICE_URL=http://credit:8083/api",
      "order/FOC_SUPPLIER_SERVICE_URL=http://supplier:8081/api",
      "order/FOC_USER_SERVICE_URL=http://user:8080/api",
    ])
    error_message = "Order must call backend services through their Service Connect names and container ports."
  }

  assert {
    condition = alltrue([
      for name, service in aws_ecs_service.app :
      one(service.service_connect_configuration).enabled
      && one(service.service_connect_configuration).namespace == var.service_connect_namespace_arn
      && one(one(service.service_connect_configuration).service).port_name == name
      && one(one(service.service_connect_configuration).service).discovery_name == name
      && one(one(one(service.service_connect_configuration).service).client_alias).dns_name == name
      && one(one(one(service.service_connect_configuration).service).client_alias).port == var.services[name].port
      && one(one(service.service_connect_configuration).log_configuration).options["awslogs-stream-prefix"] == "service-connect"
      && one(service.service_registries).registry_arn == var.service_discovery_service_arns[name]
    ])
    error_message = "Every backend must expose its named port through Service Connect and retain SRV registration during migration."
  }

  assert {
    condition = (
      one(aws_ecs_service.gateway.service_connect_configuration).enabled
      && one(aws_ecs_service.gateway.service_connect_configuration).namespace == var.service_connect_namespace_arn
      && length(one(aws_ecs_service.gateway.service_connect_configuration).service) == 0
      && one(one(aws_ecs_service.gateway.service_connect_configuration).log_configuration).options["awslogs-stream-prefix"] == "service-connect"
    )
    error_message = "The gateway must join the namespace as a client with proxy logs enabled."
  }

  assert {
    condition = alltrue([
      for name, task in aws_ecs_task_definition.app :
      tonumber(task.memory) == var.services[name].memory + 64
      && jsondecode(task.container_definitions)[0].portMappings[0].name == name
      && !contains(keys(jsondecode(task.container_definitions)[0].portMappings[0]), "appProtocol")
    ]) && tonumber(aws_ecs_task_definition.gateway.memory) == 192
    error_message = "Task definitions must name TCP ports and reserve memory for the Service Connect proxy."
  }

  assert {
    condition = toset([
      for item in jsondecode(aws_ecs_task_definition.gateway.container_definitions)[0].environment : "${item.name}=${item.value}"
      ]) == toset([
      "FOC_USER_UPSTREAM=user:8080",
      "FOC_SUPPLIER_UPSTREAM=supplier:8081",
      "FOC_ORDER_UPSTREAM=order:8082",
      "FOC_CREDIT_UPSTREAM=credit:8083",
      "FOC_REALTIME_UPSTREAM=realtime:8084",
    ])
    error_message = "The gateway must use Service Connect names without DNS re-resolution."
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

run "only_order_receives_the_enabled_ai_key" {
  command = apply

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
      for name, task in aws_ecs_task_definition.app : (
        length([for secret in jsondecode(task.container_definitions)[0].secrets : secret if secret.name == "OPENAI_API_KEY"])
        == (name == "order" ? 1 : 0)
        && alltrue([for secret in jsondecode(task.container_definitions)[0].secrets :
          secret.valueFrom == "arn:aws:ssm:ap-southeast-1:123456789012:parameter/foc-test-openai-api-key"
        if secret.name == "OPENAI_API_KEY"])
      )
    ])
    error_message = "Only order may receive the OpenAI key from the expected SSM parameter."
  }

  assert {
    condition = toset(flatten([
      for name, task in aws_ecs_task_definition.app : [for item in jsondecode(task.container_definitions)[0].environment :
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
