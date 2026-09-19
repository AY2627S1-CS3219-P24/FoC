# Local Development Setup

## Prerequisites

- JDK 25
- Docker with Docker Compose
- IntelliJ IDEA

## Setup

Create the local environment file and start **Postgres + RabbitMQ**:

```bash
  cp .env.example .env
  docker compose up -d
```

You only need to create `.env` once. RabbitMQ for the Spring apps is configured via
`SPRING_RABBITMQ_ADDRESSES` in the IntelliJ run configurations
(`amqp://foc:pass@localhost:5672`).

Also set up the keys needed for signing and verifying JWT tokens:

```bash
  mkdir -p user-service/src/main/resources/keys/
  cd user-service/src/main/resources/keys/
  openssl genpkey \
  -algorithm EC \
  -pkeyopt ec_paramgen_curve:P-256 \
  -out private.pem
  openssl pkey \
  -in private.pem \
  -pubout \
  -out public.pem
```

Copy run configurations for IntelliJ:

```bash
  mkdir -p .run/
  cp -r docs/run-configs/ .run/
```

Open the repository root in IntelliJ IDEA and wait for Gradle synchronization to finish. Ensure the project uses JDK 25.

Select the **Start all microservices** run configuration and click **Run**. Individual service configurations are also available.

| Service | Port |
|---|---:|
| User | 8080 |
| Supplier | 8081 |
| Order | 8082 |
| Credit | 8083 |

## Stop

Stop the services in IntelliJ, then stop Postgres and RabbitMQ:

```bash
docker compose down
```

IF you'd like to delete the database volumes, run this instead:

```bash
docker compose down -v
```