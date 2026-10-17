locals {
  bucket_names = { user = var.user_bucket_name, supplier = var.supplier_bucket_name }
}

module "images" {
  source  = "terraform-aws-modules/s3-bucket/aws"
  version = "5.16.1"

  for_each = local.bucket_names
  bucket   = each.value

  control_object_ownership              = true
  attach_deny_insecure_transport_policy = true
  server_side_encryption_configuration = {
    rule = {
      apply_server_side_encryption_by_default = { sse_algorithm = "AES256" }
    }
  }
}

resource "aws_iam_policy" "service_images" {
  for_each = module.images
  name     = "${var.name_prefix}-${each.key}-images"
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = ["s3:GetObject", "s3:PutObject", "s3:DeleteObject"]
      Resource = "${each.value.s3_bucket_arn}/*"
    }]
  })
}
