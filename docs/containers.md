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
`INITIAL_ADMIN_PASSWORD` in `.env.prod`. The email must use `u.nus.edu` or
`nus.edu.sg`, and the password must contain at least eight characters.

Before starting the services, [provision S3 storage](../infra/terraform/README.md)
and set `AWS_REGION`, `FOC_USER_BUCKET`, and `FOC_SUPPLIER_BUCKET` in `.env.prod`.

## First deployment

```bash
docker compose --env-file .env.prod -f compose.prod.yaml build
docker compose --env-file .env.prod -f compose.prod.yaml --profile setup run --rm admin-init
docker compose --env-file .env.prod -f compose.prod.yaml --profile setup run --rm supplier-seed
docker compose --env-file .env.prod -f compose.prod.yaml up -d
```

`admin-init` belongs to this installation. It starts the User Service image with
the `prod,admin-init` profiles, applies that service's Flyway migrations,
creates a verified admin through application code, and exits. The normal User
Service uses only `prod`. Setup inherits the usual configuration and JWT keys.

An existing admin with the same email is left unchanged. If that email already
belongs to a non-admin, setup fails and the account stays as it is. A different
email creates another admin.

`supplier-seed` inserts suppliers from the CSV files. Suppliers that are already
present stay as they are, including their opening hours.

## Later deployments

```bash
docker compose --env-file .env.prod -f compose.prod.yaml up -d --build
```

Repeat `supplier-seed` when the CSV files contain suppliers that are not in the
database yet:

```bash
docker compose --env-file .env.prod -f compose.prod.yaml --profile setup run --rm supplier-seed
```

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
