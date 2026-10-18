mock_provider "aws" {
  mock_data "aws_iam_policy_document" {
    defaults = { json = jsonencode({ Version = "2012-10-17", Statement = [] }) }
  }
}
variables {
  user_bucket_name     = "foc-test-user"
  supplier_bucket_name = "foc-test-supplier"
  name_prefix          = "foc-test"
}
override_resource {
  target = module.images["user"].aws_s3_bucket.this[0]
  values = { arn = "arn:aws:s3:::foc-test-user", id = "foc-test-user" }
}
override_resource {
  target = module.images["supplier"].aws_s3_bucket.this[0]
  values = { arn = "arn:aws:s3:::foc-test-supplier", id = "foc-test-supplier" }
}
run "service_buckets_and_permissions" {
  command = apply
  assert {
    condition     = length(module.images) == 2 && module.images["user"].s3_bucket_id != module.images["supplier"].s3_bucket_id
    error_message = "Each service must have a separate bucket."
  }
  assert {
    condition = alltrue([
      for service, policy in aws_iam_policy.service_images : (
        jsondecode(policy.policy).Statement[0].Resource == "${module.images[service].s3_bucket_arn}/*"
        && toset(jsondecode(policy.policy).Statement[0].Action) == toset(["s3:GetObject", "s3:PutObject", "s3:DeleteObject"])
        && length(jsondecode(policy.policy).Statement) == 1
      )
    ])
    error_message = "Each service policy must grant access only to its own bucket."
  }
}
