# End-to-end tests

JUnit and Playwright Java tests for the four services and frontend. Testcontainers starts the test stack defined in `compose.yaml`.

## Run

Requires Java 25 and Docker with the Compose plugin. Run from the repository root:

```bash
./gradlew :e2e-tests:test
```

Playwright downloads Chromium automatically. On Linux, install that browser's OS libraries once:

```bash
./gradlew :e2e-tests:installBrowserDependencies
```

Run a single suite:

```bash
./gradlew :e2e-tests:test --tests '*ServiceFlowsTests'
./gradlew :e2e-tests:test --tests '*BrowserJourneysTests'
```

API tests cover credit and order workflows, messaging, and recovery. Browser tests cover authentication and supplier management. `./gradlew check` includes both suites.

## Reports

- JUnit: `e2e-tests/build/reports/tests/test/`
- Browser traces: `e2e-tests/build/playwright/`
