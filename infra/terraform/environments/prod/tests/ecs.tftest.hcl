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
      partition = "aws"
    }
  }
  mock_data "aws_ssm_parameter" {
    defaults = {
      value = "ami-0123456789abcdef0"
    }
  }
}

run "images_and_hosts_stay_protected" {
  command = plan

  module {
    source = "../../modules/ecs"
  }

  variables {
    region                           = "ap-southeast-1"
    name_prefix                      = "foc-test"
    domain_name                      = "foc.example.edu"
    acme_email                       = "team@example.edu"
    mail_from                        = "noreply@mail.example.edu"
    image_tag                        = "bootstrap"
    start_services                   = false
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
    services = {
      user = {
        port = 8080, cpu = 256, memory = 768, database = "foc_user", username = "foc_user"
        jwt  = true, mail = true, bucket_env = "FOC_USER_BUCKET", scalable = false
      }
      supplier = {
        port = 8081, cpu = 256, memory = 640, database = "foc_supplier", username = "foc_supplier"
        jwt  = false, mail = false, bucket_env = "FOC_SUPPLIER_BUCKET", scalable = false
      }
      order = {
        port = 8082, cpu = 512, memory = 640, database = "foc_order", username = "foc_order"
        jwt  = false, mail = false, scalable = true
      }
      credit = {
        port = 8083, cpu = 256, memory = 512, database = "foc_credit", username = "foc_credit"
        jwt  = false, mail = false, scalable = false
      }
    }
  }

  assert {
    condition = alltrue([
      for repository in aws_ecr_repository.app :
      repository.image_tag_mutability == "IMMUTABLE" && one(repository.image_scanning_configuration).scan_on_push
    ])
    error_message = "ECR repositories must keep immutable tags and scan images on push."
  }

  assert {
    condition = alltrue([
      for host in aws_launch_template.host : alltrue([
        for device in host.block_device_mappings : one(device.ebs).encrypted
      ])
    ])
    error_message = "ECS host root volumes must be encrypted."
  }

  assert {
    condition = alltrue(concat(
      [for service in aws_ecs_service.app : one(service.deployment_circuit_breaker).enable && one(service.deployment_circuit_breaker).rollback],
      [for service in aws_ecs_service.scaled : one(service.deployment_circuit_breaker).enable && one(service.deployment_circuit_breaker).rollback],
      [one(aws_ecs_service.gateway.deployment_circuit_breaker).enable && one(aws_ecs_service.gateway.deployment_circuit_breaker).rollback],
    ))
    error_message = "ECS deployments must roll back when the circuit breaker opens."
  }
}
