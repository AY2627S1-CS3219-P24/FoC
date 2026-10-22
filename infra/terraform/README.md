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

All five services share one fixed EC2 host, with one task per service when enabled.
The host's Auto Scaling Group has minimum, desired, and maximum capacity set to one;
it replaces a failed host without adding capacity. There is no service autoscaling
or burst host pool.

Use Terraform 1.16.1, AWS provider 6.65.0, and the provider lock file.
The deployment region is fixed to `ap-southeast-1` in Terraform and the workflows.

## Inputs

| Variable | Default | Description |
| --- | --- | --- |
| `image_bucket_names` |  | Object containing globally unique `user` and `supplier` bucket names. |
| `name_prefix` | `foc-prod` | Environment prefix for AWS resource names. |
| `domain_name` |  | Public hostname for the gateway and its TLS certificate. |
| `acme_email` |  | Email address for certificate registration and budget alerts. |
| `mail_from` |  | Sender address for account emails. |
| `start_services` | `false` | Whether to run application services. |
| `core_instance_type` | `t3a.medium` | EC2 instance type for the core ECS host. |
| `db_instance_class` | `db.t4g.micro` | RDS PostgreSQL instance class. |
| `event_subscriptions` | `{}` | SNS-to-SQS routes and allowed event types. |
| `grafana_cloudwatch_reader` | `null` | Optional object containing Grafana's `account_id` and `external_id` for the CloudWatch read role. |

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
}
```

## Bootstrap

Terraform task definitions use `bootstrap` as an image placeholder. Deploy Production
replaces it with the run's immutable commit tag before updating ECS services.

Before the first infrastructure run, create SecureString SSM parameters for
`<name_prefix>-jwt-private-key`, `-jwt-public-key`, `-mail-username`, `-mail-password`,
`-initial-admin-email`, `-initial-admin-password`, `-grafana-otlp-url`, and
`-grafana-otlp-authorization`. See Grafana Cloud below for the last two values.
The database setup workflow creates and owns the service
database password parameters; reruns reuse them.

On the first deployment, run **Initialize Admin** from `main` after **Deploy
Production** has staged the release and before enabling services. It invokes
User Service with the `prod,admin-init` profiles as a one-time ECS task. After
Flyway migrations, an `ApplicationRunner` calls `UserService` to create a verified
account with `USER` and `ADMIN` roles, then closes the application context and
exits. Setup uses normal application configuration and JWT keys. Its container
publishes no ports and is not registered for service discovery. Admin creation
does not send verification mail or create a verification token.

The email must use `u.nus.edu` or `nus.edu.sg`. Email and password come from the
two `initial-admin-*` SSM parameters; the name is `System Administrator`.
Rerunning preserves an existing admin's account and password. If that email
belongs to a non-admin, setup fails without changing it. Running with another
email deliberately creates another admin; there is no permanent first-run marker.
Regular application deployments do not run initialization.

Operators can also invoke the same launcher from the repository root using AWS
credentials and the initialized Terraform backend:

```sh
bash .github/scripts/init-admin.sh
```

The launcher uses the image selected on the ECS User Service, including a staged
revision with desired count zero. It fills Terraform's non-secret task template,
registers it, and waits for its exit status. ECS injects secret values into the
setup container; the launcher does not read them. Logs use
`/ecs/<name_prefix>/database-bootstrap`, under `admin-init/admin-init/<task-id>`.
The launcher prints the task ARN, log location, and final container status.

Leave `start_services` false until three things exist: images in ECR, the
Grafana and application secrets in SSM, and the service databases created by
the database setup workflow. A later apply with `start_services` true starts
the tasks. That apply does not replace the release image.

## Operations

If a service runs out of memory, raise that service's container memory in
`locals.tf` and set `core_instance_type` to `t3a.large`. If PostgreSQL runs
out of memory or connections, set `db_instance_class` to `db.t4g.small`.

Services ignore Terraform task-definition changes so application releases control
the active revision. After applying changes to container memory, environment, or
other task settings, run **Deploy Production** from `main` to activate the updated
definitions. It reuses images already published for that commit, and builds missing
ones. An infrastructure apply alone does not activate them. Host-size changes can
replace the single core host and cause downtime; review the saved plan before applying.

Setting `start_services=false` stops all application services, including Order.
Setting it to `true` runs one task for each of the five services.

ECR images are retained until manually removed. Keep the images referenced by
active services and the previous successful deployment when pruning.

Database setup creates this deployment's service roles and databases. Reruns
preserve credentials and data; setup does not adopt or migrate unrelated databases.

## GitHub authentication and approvals

Create the GitHub OIDC provider and `AWS_INFRASTRUCTURE_ROLE` outside this stack,
then set the role ARN as a GitHub repository variable. That external authentication
setup owns both the provider and the role's permissions and trust.

Configure the `production` GitHub environment with required reviewers and a
deployment restriction to `main`. Application deployment, infrastructure apply,
database setup, and admin initialization use that environment. Image publication
and infrastructure planning use the main-branch identity. The external role must
allow both OIDC subjects, with audience `sts.amazonaws.com`. For this repository's immutable
subject format, the default subjects are:

```text
repo:AY2627S1-CS3219-P24@327339601/FoC@1365752578:ref:refs/heads/main
repo:AY2627S1-CS3219-P24@327339601/FoC@1365752578:environment:production
```

Verify the actual subjects if organization/repository OIDC customization is used;
see [GitHub's OIDC reference](https://docs.github.com/en/actions/reference/security/oidc).
These GitHub settings and the external role's current trust were not verified by
local Terraform tests. Environment association alone does not create reviewers
or restrict what the externally managed role can do.

Admin initialization uses the existing role's Terraform state access plus
`ecs:DescribeServices`, `ecs:DescribeTaskDefinition`, `ecs:RegisterTaskDefinition`,
`ecs:RunTask`, and `ecs:DescribeTasks`. It also needs `iam:PassRole` for the
bootstrap execution role. The setup container receives only the service database
password and initial-admin parameters, without the database master password.

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

Supplier, Order, and Credit fetch public verification keys through this HTTPS
gateway. The JWKS response contains public keys only; it need not be private.
The services can start before the certificate is trusted, but fresh JWT key
fetches fail until ACME succeeds. ECS rollout completion does not establish TLS
or authentication readiness. After enabling services, check without bypassing
certificate verification (replace the hostname):

```sh
curl --fail --silent --show-error https://YOUR-DOMAIN/api/.well-known/jwks.json
```

Confirm the response contains public verification keys, then log in and exercise
protected service endpoints. If the certificate is not
ready, inspect `renew-certificate.service` and its timer through SSM. Do not use
`curl -k` or disable JVM certificate validation. Internal JWKS routing can be added
later if required; bridge-mode Cloud Map uses SRV records and dynamic ports, so a
plain internal hostname is not a drop-in replacement for this URL.

Set each image bucket environment variable from the `image_bucket_names` output.
Set `AWS_REGION` to `ap-southeast-1`. Leave
`FOC_S3_ENDPOINT` and `FOC_S3_PATH_STYLE` unset in AWS.

Terraform attaches each image policy to its owning ECS task role.

## Messaging

Each service has an SNS topic, an SQS queue, and a DLQ. Topics and queues use
`${name_prefix}-${service}-events`. DLQs use `${name_prefix}-${service}-dlq`.
Topics and queues use a rotating customer-managed KMS key. Queue policies allow
SNS delivery only from the same account and the subscribed topics, and deny
non-TLS access. Failed messages move to the DLQ after five receives.

`event_subscriptions` in `terraform.tfvars` selects the event types. Production
routes `UserCreatedEvent` from User to Credit, errand completion, cancellation,
and expiry from Order to Credit, and `CreditSettlementResultEvent` from Credit
to Order. Subscriptions deliver raw JSON.

Copy `deployment.topics` and `deployment.queue_names` into the topic and queue
variables in `.env.prod.example`. ECS task roles can publish to their own topic,
consume their own queue, and use the messaging key only through SNS or SQS.

The deployment and setup workflows share the production lock. Set up a new environment in this order:

1. Update infrastructure with `start_services` false in `terraform.tfvars`. It plans and applies that file.
2. Set up databases. It creates the service databases and does not change infrastructure.
3. Run Deploy Production from `main`. It tests, publishes missing images, and stages those images on the Terraform task definitions.
4. Run **Initialize Admin** from `main`. It uses the staged User Service image, applies its Flyway migrations, and creates the admin through application code.
5. Update infrastructure again with `start_services` true. That apply changes desired counts; services keep the release image. After HTTPS is ready, log in with the configured admin email and password.

**Deploy Production** is manual and has no inputs. Run it from `main`; runs from
other branches are skipped. Every job uses the same `github.sha` captured for that
run, even if `main` advances while tests are running. Backend and frontend checks
always run before publication. Images use that commit SHA as their immutable tag.

Publication reuses existing commit tags and builds only missing images. Re-run a
failed workflow to retry its original commit; start a new run to deploy the latest
`main`. A new commit builds all five images, including configuration-only commits.
A release with services disabled only stages task definitions; it does not
validate running applications. A five-service matrix uses Amazon's ECS render and
deploy GitHub Actions to set the image and desired count. Active releases wait for
deployment, and the deploy action reports a circuit-breaker rollback as failure.
Disabled releases explicitly set zero desired tasks and skip the runtime wait.
Each service records its registered task-definition ARN in the run summary.
Inspect service events before retrying a failed or partial release; services roll
back individually, so a failed release can leave a mix of versions.

### Rollback

The ECS circuit breaker automatically attempts to restore the previous completed
deployment when a new rollout fails. Keep that deployment's task definitions and
images available. Reverting the code change on `main` and running Deploy Production
again is the normal way to undo a change; it produces a new release.

For an emergency rollback of an affected service, use the exact task-definition
ARN from a previous successful Deploy Production run's summary. Do not assume the
previous numeric revision was ever deployed successfully. Check that its images
are still in ECR before updating the service. Run these commands with the deployment
role, using the actual cluster, service, and known working ARN:

```sh
export AWS_DEFAULT_REGION=ap-southeast-1
cluster=foc-prod
service=user
previous_task_definition='arn:aws:ecs:ap-southeast-1:YOUR-ACCOUNT:task-definition/foc-prod-user:KNOWN-WORKING-REVISION'
aws ecs describe-task-definition --task-definition "$previous_task_definition" \
  --query 'taskDefinition.containerDefinitions[].{name:name,image:image}'
aws ecs update-service --cluster "$cluster" --service "$service" \
  --task-definition "$previous_task_definition"
aws ecs wait services-stable --cluster "$cluster" --services "$service"
aws ecs describe-services --cluster "$cluster" --services "$service" \
  --query 'services[].{taskDefinition:taskDefinition,deployments:deployments}'
```

Confirm the selected ARN is the primary deployment with rollout state `COMPLETED`;
stability alone does not confirm the rollback target. Then check the application.
Wait for all production workflows to finish before a manual rollback; these CLI
commands do not acquire their concurrency lock. After rollback, revert or fix
`main` before starting another Deploy Production run.

For host bootstrap problems, use SSM to inspect `/var/log/host-bootstrap.log`,
`/var/log/cloud-init-output.log`, and `systemctl status docker metadata-firewall ecs`.
Also inspect `host-prepare`. The selected ECS-optimized AL2023 AMI
includes AWS CLI v2; `aws --version` identifies the installed version.
Check that the ECS host registered. Local checks do not prove EC2 boot order,
firewall behavior, stopping services, or public DNS/certificate readiness.

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

The infrastructure workflow checks all eight externally supplied secrets before planning.

To graph ECS, EC2, and RDS in Grafana, add a CloudWatch data source that assumes a role. Grafana shows its AWS account id and an external id. Set those in `terraform.tfvars` and apply:

```hcl
grafana_cloudwatch_reader = {
  account_id  = "123456789012"
  external_id = "copy-from-grafana"
}
```

After apply, the `grafana_cloudwatch_role_arn` output is the role ARN to paste into the data source. `GetMetricData`, `GetMetricStatistics`, and `ListMetrics` do not accept a namespace condition, so the role can read CloudWatch metrics in the account. Log reads are limited to `/ecs/<name_prefix>/`.

Each production task adds `service.instance.id` from the container hostname. Docker sets that hostname to the container id, so replicas and overlapping deploys publish separate series. The dashboard legend includes that id.

Import [`grafana/foc-overview.json`](grafana/foc-overview.json). Choose the stack's Prometheus data source and the CloudWatch data source. The same log lines are in the CloudWatch console. Leave the dashboard on manual refresh. If you turn auto-refresh on, use 1 minute or slower so CloudWatch `GetMetricData` stays small.

## Local checks

These checks use a mocked AWS provider and do not create cloud resources:

```sh
terraform -chdir=infra/terraform/environments/prod init -backend=false -lockfile=readonly
terraform -chdir=infra/terraform fmt -check -recursive
terraform -chdir=infra/terraform/environments/prod validate
terraform -chdir=infra/terraform/environments/prod test
```
