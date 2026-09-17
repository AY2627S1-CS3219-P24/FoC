# Local Development Setup

## Prerequisites

- JDK 25
- Docker with Docker Compose
- IntelliJ IDEA

## Setup

Create the local environment file and start PostgreSQL:

```bash
  cp .env.example .env
  docker compose up -d
```

You only need to create `.env` once.

Open the repository root in IntelliJ IDEA and wait for Gradle synchronization to finish. Ensure the project uses JDK 25.

Select the **Start all microservices** run configuration and click **Run**. Individual service configurations are also available.

| Service | Port |
|---|---:|
| User | 8080 |
| Supplier | 8081 |
| Order | 8082 |
| Credit | 8083 |

## Stop

Stop the services in IntelliJ, then stop PostgreSQL:

```bash
docker compose down
```

IF you'd like to delete the database volumes, run this instead:

```bash
docker compose down -v
```