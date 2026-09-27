# Run the backend in containers

Run from the repository root with Docker Compose and OpenSSL installed.

Have the deployment environment or CD pipeline supply the variables listed in
[`.env.prod.example`](../.env.prod.example). An environment file is not required.

Provide JWT key files on the deployment host using `JWT_PRIVATE_KEY_FILE` and
`JWT_PUBLIC_KEY_FILE`, or generate them once at the default paths:

```bash
mkdir -p secrets
chmod 700 secrets
openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out secrets/jwt-private.pem
openssl pkey -in secrets/jwt-private.pem -pubout -out secrets/jwt-public.pem
chmod 444 secrets/jwt-private.pem secrets/jwt-public.pem
```

Build and start the backend, Postgres, RabbitMQ, and nginx:

```bash
docker compose -f compose.prod.yaml up -d --build
```

The API is at [https://localhost](https://localhost) using a self-signed certificate. Run the frontend
separately. This stack (`foc`) can run alongside local development (`foc-dev`),
with separate data and host ports.

Stop the stack, retaining data:

```bash
docker compose -f compose.prod.yaml down
```

## Test the containerized stack locally

To try the containerized setup on a local development machine, copy the example
environment file and fill in the required values:

```bash
cp .env.prod.example .env.prod
chmod 600 .env.prod
```

Generate local JWT keys if they do not already exist:

```bash
mkdir -p secrets
chmod 700 secrets
openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out secrets/jwt-private.pem
openssl pkey -in secrets/jwt-private.pem -pubout -out secrets/jwt-public.pem
chmod 444 secrets/jwt-private.pem secrets/jwt-public.pem
```

Then build and start the production-style stack using the local environment
file:

```bash
docker compose --env-file .env.prod -f compose.prod.yaml up -d --build
```

Check that the containers are running:

```bash
docker compose --env-file .env.prod -f compose.prod.yaml ps
```

If a service fails to start, inspect its logs:

```bash
docker compose --env-file .env.prod -f compose.prod.yaml logs -f
```

The API is available at [https://localhost](https://localhost). Because the local
nginx setup uses a self-signed certificate, the browser or HTTP client may show
a certificate warning.

When finished, stop the local containerized stack while retaining its data:

```bash
docker compose --env-file .env.prod -f compose.prod.yaml down
```

To also remove its persisted volumes and start again from a clean state:

```bash
docker compose --env-file .env.prod -f compose.prod.yaml down -v
```