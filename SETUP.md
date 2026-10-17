# Local Development Setup

## Prerequisites

- JDK 25
- Docker with Docker Compose
- Node.js 22.12 or newer and npm
- IntelliJ IDEA

## Setup

Create the local environment file and start **Postgres + RabbitMQ + FLoCI**:

```bash
  cp .env.example .env
  docker compose up -d --wait
```

Private image storage is initialized automatically in FLoCI on `localhost:4566`.
The S3 migrations clear existing development image keys. Reupload avatars and supplier images after updating.

You only need to create `.env` once. RabbitMQ for the Spring apps is configured via
`SPRING_RABBITMQ_ADDRESSES` in the IntelliJ run configurations
(`amqp://foc:pass@localhost:5672`).

Also set up the keys needed for signing and verifying JWT tokens:

```bash
  mkdir -p user-service/src/main/resources/keys/
  openssl genpkey \
  -algorithm EC \
  -pkeyopt ec_paramgen_curve:P-256 \
  -out user-service/src/main/resources/keys/private.pem
  openssl pkey \
  -in user-service/src/main/resources/keys/private.pem \
  -pubout \
  -out user-service/src/main/resources/keys/public.pem
```

Copy run configurations for IntelliJ:

```bash
  mkdir -p .run/
  cp docs/run-configs/*.run.xml .run/
```

Open the repository root in IntelliJ IDEA and wait for Gradle synchronization to finish. Ensure the project uses JDK 25.

### Email verification setup

User Service sends registration verification emails through SMTP. Each developer
can use their own Gmail account as the sender; register test accounts with a NUS
email address whose inbox you can access.

1. Enable **2-Step Verification** on your Google account.
2. Create an [app password](https://myaccount.google.com/apppasswords) named
   `FoC`. Save the generated password for the configuration
   below. See [Google's app password guide](https://support.google.com/accounts/answer/185833)
   if the option is unavailable.
3. In IntelliJ, open **Run → Edit Configurations → UserServiceApplication**.
   Keep **Store as project file** enabled and check that its location is
   `.run/UserServiceApplication.run.xml`, using the copy created above.
   If it points to `docs/run-configs/`, change it to `.run/` before adding
   credentials. The `.run/` directory is ignored by Git; `docs/run-configs/`
   contains the shared templates and must not contain personal credentials.
4. Open **Environment variables** and add these values, preserving the existing
   database and RabbitMQ variables and the `dev` active profile:

   | Variable           | Value                                                                       |
   | ------------------ | --------------------------------------------------------------------------- |
   | `MAIL_HOST`        | `smtp.gmail.com`                                                            |
   | `MAIL_PORT`        | `587`                                                                       |
   | `MAIL_USERNAME`    | Your full Gmail address                                                     |
   | `MAIL_PASSWORD`    | The generated app password, without spaces; not your Gmail sign-in password |
   | `MAIL_FROM`        | The same Gmail address as `MAIL_USERNAME`                                   |
   | `FOC_FRONTEND_URL` | `http://localhost:3000`                                                     |

5. Click **Apply → OK**, then start or restart User Service with that configured
   run configuration. The saved values are reused on subsequent launches.

The locally launched Spring Boot service does not automatically read the root
`.env`. If you use terminal `bootRun` instead of IntelliJ, set the environment
variables in the terminal that launches it; IntelliJ's values are not inherited.

### Start the application

Select the **Start all microservices** run configuration and click **Run**. Individual service configurations are also available.

On Windows, if IntelliJ reports **Command line is too long**, open the affected
run configuration, select **Modify options → Shorten command line**, and choose
**@argFiles (Java 9+)**. Save it and run again.

| Service  | Port |
| -------- | ---: |
| User     | 8080 |
| Supplier | 8081 |
| Order    | 8082 |
| Credit   | 8083 |

In a second terminal, start the frontend from the repository root:

```bash
cd frontend
npm ci
npm run dev
```

Open [http://localhost:3000](http://localhost:3000). Vite forwards `/api`
requests to the local backend services.

To check email delivery, register an unused NUS email address, check its inbox
and spam folder, and open the verification link on the computer running the
frontend. The link confirms the email automatically; then follow **Log in** and
sign in with the registered password. A `201` registration response alone does
not confirm that the email was delivered.

## Stop

Stop the frontend dev server and services in IntelliJ, then stop Postgres and RabbitMQ:

```bash
docker compose down
```

If you'd like to delete the database volumes, run this instead:

```bash
docker compose down -v
```
