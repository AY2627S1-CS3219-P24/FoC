# Run FoC in containers

Run these commands from the repository root with Docker Compose and OpenSSL installed.

## Setup

Create `.env.prod` from [`.env.prod.example`](../.env.prod.example) and set the
required values. The commands below select it explicitly, leaving the local
development `.env` separate.

```bash
cp .env.prod.example .env.prod
```

Generate JWT keys once at the default paths, or set `JWT_PRIVATE_KEY_FILE` and
`JWT_PUBLIC_KEY_FILE` to existing key files:

```bash
mkdir -p secrets
chmod 700 secrets
openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out secrets/jwt-private.pem
openssl pkey -in secrets/jwt-private.pem -pubout -out secrets/jwt-public.pem
chmod 444 secrets/jwt-private.pem secrets/jwt-public.pem
```

Set `INITIAL_ADMIN_EMAIL`, `INITIAL_ADMIN_NAME`, and
`INITIAL_ADMIN_PASSWORD` in `.env.prod`.

## Private image storage

Provision the service buckets and IAM policies using [the Terraform storage stack](../infra/terraform/README.md),
attach the returned policies to the AWS runtime roles, and set `AWS_REGION`,
`FOC_USER_BUCKET`, and `FOC_SUPPLIER_BUCKET` in `.env.prod` before starting the services.
Do not set local emulator endpoints in production.

The Spring Cloud AWS S3 starter configures the client and presigner through
`spring.cloud.aws` properties. Local credentials and the emulator endpoint live in
`application-dev.yaml`; production uses the default AWS credentials chain.
Service-owned bucket names remain under `storage.s3.bucket`.

The S3 adapters handle generic object operations. `AvatarStorageService` and
`SupplierImageStorageService` own image keys, content types, and signed URL settings.
Replacing or removing an image publishes a local Spring event. An asynchronous
listener deletes the previous object after the database transaction commits.
Failed database updates publish an event to delete the new upload instead.

Deletion events are persisted by Spring Modulith in the owning service's database
and handled asynchronously after commit. Failed or interrupted deletions are replayed
on restart. Completed publications are deleted immediately. There are no bucket scans,
database reference queries, or periodic retry jobs. Outstanding events can also be
resubmitted through Modulith's `IncompleteEventPublications` API. Only image deletion listeners use the registry,
so verification-mail tokens are not stored in it.

## First deployment

```bash
docker compose --env-file .env.prod -f compose.prod.yaml build
docker compose --env-file .env.prod -f compose.prod.yaml --profile setup run --rm admin-init
docker compose --env-file .env.prod -f compose.prod.yaml up -d
```

## Later deployments

```bash
docker compose --env-file .env.prod -f compose.prod.yaml up -d --build
```

`admin-init` runs only on first deployment.

## Operate

Open [https://localhost](https://localhost). The `api-gateway` serves the
frontend and routes `/api` requests to the backend services. Its default
self-signed certificate causes a browser warning.

To check container status or inspect logs:

```bash
docker compose --env-file .env.prod -f compose.prod.yaml ps
docker compose --env-file .env.prod -f compose.prod.yaml logs -f
```

Stop the stack with `docker compose --env-file .env.prod -f compose.prod.yaml down`.
Add `-v` to remove its data volumes as well.
