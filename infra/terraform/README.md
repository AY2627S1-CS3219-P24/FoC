# AWS deployment

This Terraform configuration deploys FoC to AWS. Local development uses
`../../compose.yaml` and does not need Terraform or a state bucket.

One private S3 bucket per service, using `terraform-aws-modules/s3-bucket/aws`
5.16.1. Buckets block public access, use AES256 encryption, and require HTTPS.
Each service policy grants only `GetObject`, `PutObject`, and `DeleteObject`
on its own bucket. Compute and runtime roles are managed separately.

The production environment always creates the network, database, messaging,
and ECS resources. PostgreSQL is single-AZ. Its security group accepts
port 5432 only from the ECS hosts. The gateway runs as an ECS container:
it serves the frontend, terminates HTTPS, and proxies to the backend services.
`mail_from` is the account-email sender and does not have to match `domain_name`.

Use Terraform 1.16.1, AWS provider 6.65.0, and the provider lock file.

## Inputs

| Variable | Default | Description |
| --- | --- | --- |
| `region` | `ap-southeast-1` | AWS deployment region. |
| `image_bucket_names` |  | Globally unique image bucket names keyed by owning service. |
| `name_prefix` | `foc-prod` | Environment prefix for AWS resource names. |
| `domain_name` |  | Public hostname for the gateway and its TLS certificate. |
| `acme_email` |  | Email address for certificate registration and budget alerts. |
| `mail_from` |  | Sender address for account emails. |
| `image_tag` |  | Container image tag shared by all application services. |
| `start_services` | `false` | Whether to run application services. |
| `core_instance_type` | `t3a.medium` | EC2 instance type for the core ECS host. |
| `db_instance_class` | `db.t4g.micro` | RDS PostgreSQL instance class. |
| `event_subscriptions` | `{}` | SNS-to-SQS routes and allowed event types. |
| `github_oidc_provider_arn` | `""` | Existing GitHub OIDC provider ARN; empty creates a provider. |
| `grafana_cloudwatch_account_id` | `""` | Grafana Cloud AWS account ID trusted to assume the CloudWatch read role; empty disables role creation. |
| `grafana_cloudwatch_external_id` | `""` | External ID required to assume the Grafana CloudWatch read role. |

Set both Grafana inputs or leave both empty.

`event_subscriptions` is a map of objects:

```hcl
event_subscriptions = {
  order_created = {
    publisher   = "order"
    consumer    = "credit"
    event_types = ["OrderCreated"]
  }
}
```

Service task settings are not tfvars. Production defines them in
[`locals.tf`](environments/prod/locals.tf). Each `services` entry is:

```hcl
user = {
  port       = 8080
  cpu        = 256
  memory     = 768
  database   = "foc_user"
  username   = "foc_user"
  jwt        = true
  mail       = true
  bucket_env = "FOC_USER_BUCKET" # optional
  scalable   = false
}
```

## Bootstrap

Set `image_tag` to `bootstrap` before container images exist. After the first
release, set it to the immutable Git commit tag published to ECR.

Leave `start_services` false until three things exist: images in ECR, the
Grafana and application secrets in SSM, and the service databases created by
the database setup workflow. A later apply with `start_services` true starts
the tasks. That apply does not replace the release image.

## Operations

If a service runs out of memory, raise that service's container memory in
`locals.tf` and set `core_instance_type` to `t3a.large`. If PostgreSQL runs
out of memory or connections, set `db_instance_class` to `db.t4g.small`.

## Deploy

Authenticate with AWS SSO or the deployment role. Before the first deployment,
create the S3 bucket where Terraform will save its record of AWS resources.
Choose a globally unique name below. Run these commands once, from the repository root:

```sh
state_bucket="YOUR-GLOBALLY-UNIQUE-TERRAFORM-STATE-BUCKET"
aws s3api create-bucket --bucket "$state_bucket" --region ap-southeast-1 \
  --create-bucket-configuration LocationConstraint=ap-southeast-1
aws s3api put-bucket-versioning --bucket "$state_bucket" --region ap-southeast-1 \
  --versioning-configuration Status=Enabled
```

Set the GitHub repository variable `TF_STATE_BUCKET` to that bucket name. The
workflows pass it to `terraform init`. The state key, region, and locking stay
in [`backend.tf`](environments/prod/backend.tf).

Edit [`terraform.tfvars`](environments/prod/terraform.tfvars) before the first
apply. Replace `domain_name`, `acme_email`, and `mail_from`. The image buckets
are `foc-prod-user` and `foc-prod-supplier`. See Bootstrap above before setting
`start_services` to true.

For a local apply, copy `backend.hcl.example` to `backend.hcl` and set `bucket`
there:

```sh
terraform -chdir=infra/terraform/environments/prod init -backend-config=backend.hcl
terraform -chdir=infra/terraform/environments/prod plan -out=tfplan
terraform -chdir=infra/terraform/environments/prod show tfplan
# After reviewing and approving the saved plan:
terraform -chdir=infra/terraform/environments/prod apply tfplan
```

Point the public domain at the `elastic_ip` output before starting the gateway.
The gateway serves HTTPS immediately with a temporary certificate. Within a few
minutes the host timer requests a real certificate, reloads Nginx, and stores
it in S3. A replacement host restores that archive during host preparation.

Set each image bucket environment variable from the `image_bucket_names` output.
set `AWS_REGION` to the configured region (default `ap-southeast-1`). Leave
`FOC_S3_ENDPOINT` and `FOC_S3_PATH_STYLE` unset in AWS.

Terraform attaches each image policy to its owning ECS task role.

Three workflows share the production lock. Run them in this order for a new environment:

1. Update infrastructure with `start_services` false in `terraform.tfvars`. It plans and applies that file.
2. Set up databases. It creates the service databases and does not change infrastructure.
3. Deploy Production for a release. It tests, publishes images, and registers those images on the Terraform task definitions.
4. Update infrastructure again with `start_services` true. That apply changes desired counts. Services keep the release image. Create the Grafana parameters below before this apply.

## Grafana Cloud

Container Insights stays disabled. The four Spring services push heap, GC, process CPU, HTTP, and Hikari metrics to Grafana Cloud once a minute. Application logs stay in CloudWatch for 7 days under `/ecs/<name_prefix>/user`, `supplier`, `order`, `credit`, `gateway`, and `database-bootstrap`. At a quiet log level that is about $1–2 for the month.

Create a Grafana Cloud free stack and an access-policy token with the `metrics:write` scope. In the stack, open the OpenTelemetry endpoint and append `/v1/metrics` if the shown URL ends at `/otlp`. Store that URL and a Basic authorization header as SecureString parameters. The header value is `Basic` plus a space plus the base64 encoding of `<instance id>:<token>`, with no trailing newline. Replace `foc-prod` with `name_prefix` when that differs:

```sh
prefix=foc-prod
aws ssm put-parameter --name "$prefix-grafana-otlp-url" --type SecureString \
  --value 'https://otlp-gateway-prod-REGION.grafana.net/otlp/v1/metrics'
aws ssm put-parameter --name "$prefix-grafana-otlp-authorization" --type SecureString \
  --value "Basic $(printf '%s' "$GRAFANA_INSTANCE_ID:$GRAFANA_TOKEN" | base64)"
```

The infrastructure workflow checks both parameters before an apply that sets `start_services` to true.

To graph ECS, EC2, and RDS in Grafana, add a CloudWatch data source that assumes a role. Grafana shows its AWS account id and an external id. Set those in `terraform.tfvars` and apply:

```hcl
grafana_cloudwatch_account_id = "123456789012"
grafana_cloudwatch_external_id    = "copy-from-grafana"
```

After apply, the `grafana_cloudwatch_role_arn` output is the role ARN to paste into the data source. `GetMetricData`, `GetMetricStatistics`, and `ListMetrics` do not accept a namespace condition, so the role can read CloudWatch metrics in the account. Log reads are limited to `/ecs/<name_prefix>/`.

Each production task adds `service.instance.id` from the container hostname. Docker sets that hostname to the container id, so replicas and overlapping deploys publish separate series. The dashboard legend includes that id.

Import [`grafana/foc-overview.json`](grafana/foc-overview.json). Choose the stack's Prometheus data source and the CloudWatch data source. The same log lines are in the CloudWatch console. Leave the dashboard on manual refresh. If you turn auto-refresh on, use 1 minute or slower so CloudWatch `GetMetricData` stays small.

## Local checks

These checks use a mocked AWS provider and do not create cloud resources:

```sh
terraform -chdir=infra/terraform/environments/prod init -backend=false
terraform -chdir=infra/terraform/environments/prod fmt -check
terraform -chdir=infra/terraform/environments/prod validate
terraform -chdir=infra/terraform/environments/prod test
```
