# AGENTS.md

## Repository

Friend on Campus (FoC) is a Gradle multi-project monorepo containing Spring Boot microservices.

Services are organized as independent Gradle modules and own their source code, resources, migrations, and configuration.

Java packages are rooted at:

```text
com.cs3219.foc.<domain>
```

## Service structure

Services follow a package-by-responsibility structure:

| Package                    | Responsibility                                                                         |
| -------------------------- | -------------------------------------------------------------------------------------- |
| `controller`               | HTTP endpoints and request/response handling                                           |
| `service`                  | Application operations, orchestration, transactions, and application event publication |
| `repository`               | Persistence access                                                                     |
| `model/entity`             | JPA entities and related enums                                                         |
| `model/dto`                | Request, response, transfer, and event records                                         |
| `model/dto/event`          | Event contracts representing completed application facts                               |
| `mapper`                   | Entity/DTO mapping                                                                     |
| `infrastructure/messaging` | Asynchronous messaging transport and integration infrastructure                        |
| `config`                   | Application and framework configuration                                                |
| `security`                 | Authentication and security infrastructure                                             |
| `exception`                | Application exceptions and global error handling                                       |
| `scheduler`                | Scheduled tasks                                                                        |

The typical synchronous request flow is:

```text
controller → service → repository
```

Controllers expose DTOs rather than persistence entities.

Dependencies use constructor injection.

## Messaging

Asynchronous service integration uses Amazon SNS/SQS through Spring Cloud AWS.

Services own their event contracts, SNS producers and SQS consumers. Configure SNS/SQS bindings in Terraform and the owning service’s AWS profile.

Messaging infrastructure is organized as:

```text
infrastructure/
└── messaging/
    ├── consumer/
    ├── producer/
    └── processor/
```

* `consumer` receives integration events.
* `producer` publishes integration events.
* `processor` consumes an event and produces another event.

Event contracts live under:

```text
model/
└── dto/
    └── event/
```

Events are records representing completed facts, for example:

```text
UserCreatedEvent
CreditsAllocatedEvent
```

Application services may create and publish these events through Spring's `ApplicationEventPublisher`.

Messaging infrastructure may listen for the same event types and transport them through Amazon SNS.

Messaging bindings and transport configuration live in the owning service's application configuration.

## Persistence

Persistence uses Spring Data JPA.

Database schema changes are managed through Flyway migrations under:

```text
src/main/resources/db/migration/
```

Persisted models live under `model/entity`.

## Configuration

Service configuration lives under:

```text
src/main/resources/
```

Typed application properties use Spring `@ConfigurationProperties` classes under `config`.

Configuration belongs to the service that owns the corresponding behavior.

## Naming

Classes are named by responsibility:

```text
AuthController
UserService
UserRepository
UserMapper
SecurityConfig
AuthProperties
RefreshTokenCleanupJob
```

DTOs use records and role-oriented names such as:

```text
RegisterUserRequest
AccessTokenResponse
UserProfileDto
```

Entities use domain names without an `Entity` suffix.

Integration events use completed-fact names ending in `Event`.

Service directories use kebab-case:

```text
user-service
```

Java domain packages use singular domain names:

```text
com.cs3219.foc.user
```

## Local runtime

`SETUP.md` is the local setup guide. Cloud Agents start Docker, then `docker compose up -d` from the repository root, which runs Postgres and FLoCI. Copy `.env.example` to `.env` before Compose. User Service needs the JWT key pair described in `SETUP.md`.

| Process | Port |
| ------- | ---: |
| User Service | 8080 |
| Supplier Service | 8081 |
| Order Service | 8082 |
| Credit Service | 8083 |
| Frontend (`npm run dev` in `frontend/`) | 3000 |

Each service uses the `dev` Spring profile and the `SPRING_DATASOURCE_*` values in `docs/run-configs`.

`./gradlew check` uses Testcontainers, which needs a readable Docker socket. `UserProfilePersistenceTests` also needs a `foc_test` database and:

```text
FOC_TEST_DATABASE_URL=jdbc:postgresql://localhost:5432/foc_test
FOC_TEST_DATABASE_USERNAME=foc
FOC_TEST_DATABASE_PASSWORD=pass
```

`FOC_TEST_DATABASE_PASSWORD` matches `POSTGRES_PASSWORD` in `.env.example`.
