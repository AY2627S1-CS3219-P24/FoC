# Image storage

One private S3 bucket per service, using `terraform-aws-modules/s3-bucket/aws`
5.16.1. Buckets block public access, use AES256 encryption, and require HTTPS.
Each service policy grants only `GetObject`, `PutObject`, and `DeleteObject`
on its own bucket. Compute and runtime roles are managed separately.

Use Terraform 1.16.1, AWS provider 6.65.0, and the provider lock file.

## Deploy

Authenticate with your AWS profile/SSO or deployment role. Copy:

- `backend.hcl.example` to `backend.hcl`: use an existing private, encrypted,
  versioned S3 state bucket, separate from image storage. Locking is enabled.
- `production.tfvars.example` to `production.auto.tfvars`: choose globally unique
  bucket names. Use separate state keys and name prefixes for other environments.

```sh
terraform -chdir=infra/terraform init -backend-config=backend.hcl
terraform -chdir=infra/terraform plan -out=tfplan
terraform -chdir=infra/terraform show tfplan
# After reviewing and approving the saved plan:
terraform -chdir=infra/terraform apply tfplan
```

Set `FOC_USER_BUCKET` and `FOC_SUPPLIER_BUCKET` from the `bucket_names` output;
set `AWS_REGION` to the configured region (default `ap-southeast-2`). Leave
`FOC_S3_ENDPOINT` and `FOC_S3_PATH_STYLE` unset in AWS.

Attach each `service_policy_arns` output to its service's ECS task role.
For Compose on EC2, attach both policies to the instance role and make IMDSv2
credentials reachable from containers; that shared role can access both buckets.

## Local checks

These checks use a mocked AWS provider and do not create cloud resources:

```sh
terraform -chdir=infra/terraform init -backend=false
terraform -chdir=infra/terraform fmt -check
terraform -chdir=infra/terraform validate
terraform -chdir=infra/terraform test
```
