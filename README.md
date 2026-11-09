# NPSB Dispute Email Automation — backend (Spring Boot)

Spring Boot port of the FastAPI backend. Same REST API (paths, request/response JSON, status codes and
error messages), same MySQL tables and columns, so the existing React frontend and an existing
database work unchanged. Passwords, JWT sessions and the encrypted System Settings secrets stored by
the Python version stay valid (same hashing, token and encryption formats).

Stack: Java 17, Spring Boot 4.1 (Spring MVC on embedded Tomcat, Spring Data JPA / Hibernate 7,
Spring Security, Actuator, Jakarta Mail), springdoc-openapi (Swagger UI), SLF4J + Logback, Gradle.

## Build

Needs JDK 17+. Gradle itself comes with the project (the wrapper downloads it on first use).

    ./gradlew build          # Windows: gradlew.bat build

This runs all tests (including the HTTP contract test) and makes one runnable jar:
`build/libs/npsb-backend.jar` (`./gradlew bootJar` makes only the jar).

While developing, `./gradlew bootRun` starts the API straight from the sources (same `.env`);
pass arguments with `--args`, e.g. `./gradlew bootRun --args="--port 8001"`.

Dependency versions are in `gradle/libs.versions.toml` (Spring Boot manages most of them), the build in
`build.gradle.kts`.

## Run

1. Copy `.env.example` to `.env` (next to where you start the jar) and fill in the database,
   mail server and `JWT_SECRET`. There is no self sign-up: the built-in admin (username `admin`,
   password `Admin@1234`) is created on first start and creates every other account. Change its
   password on My Profile straight away.
2. Start MySQL. The database and all tables are created automatically on first start; older
   databases get missing columns added, exactly like the Python version did.
3. Start the API:

       java -jar build/libs/npsb-backend.jar                  # host/port from .env (default 127.0.0.1:8000)
       java -jar build/libs/npsb-backend.jar --port 8000 --host 0.0.0.0

- API docs: http://localhost:8000/docs (Swagger UI) and http://localhost:8000/openapi.json. Sign in with
  `POST /api/auth/login`, then use the access token with the Authorize button.
- Health probes (no sign-in): `/startupz`, `/livez`, `/readyz` (same answers as before) and Spring Boot's
  `/actuator/health`, `/actuator/health/liveness`, `/actuator/health/readiness`.

### Docker

The `Dockerfile` builds the jar with the Gradle wrapper inside Docker (no local JDK needed) and runs it on a
Java 21 JRE as a non-root user (NGD-CTR-001). All settings come from the environment at run time.

    docker compose up -d --build     # this backend + its own MySQL (project "npsb-backend"), API on :8000
    docker compose logs -f backend
    docker compose down              # add -v to also delete the database

`DB_PASSWORD` in `.env` must not be empty: it becomes the MySQL container's root password. MySQL Workbench from
the computer: `127.0.0.1:3307`. For the whole app (frontend + backend + MySQL) use the `docker-compose.yml` in
the frontend folder instead; run only one of the two stacks at a time (same ports).

### Case IDs

Every email to a partner gets a case ID when it is first prepared (`service/CaseIds`), e.g.
`202611000031000001`: year and month, `00`, partner id (3 digits), `1` inward / `2` outward, `000`, and the
partner's running number for that mail type in that month. It is shown in the app, searchable on Sent Mails,
in the Excel exports, and sent as invisible text with every email (first email, follow-ups, answers). A reply
whose headers don't link it to an email, but which quotes ours, is linked by that case ID (`matched_by =
"case_id"`). Emails made before case IDs existed get one at startup.

### Configuration

All settings are listed in `src/main/resources/application.yml` with their `.env` name and default
(`${SMTP_HOST:localhost}` reads `SMTP_HOST`). Values come from the environment or the `.env` file
(the environment wins); `.env` is read with python-dotenv's rules, so the old files keep working.
`NPSB_ENV_FILE` picks another file, or `none` for no file. Any Spring property can also be given as usual
(`--server.port=8001`, `SPRING_...` environment variables).

The settings admins change on the System Settings page are kept in the database and applied at startup and
at once when changed (`config/LiveSettings`); the `.env` values are their defaults.

### Logging

SLF4J with Logback (`src/main/resources/logback-spring.xml`): the console, and the application log on the
server (never shown in the portal): `APP_LOG_DIR` (default `app/logs` in the backend folder), `app.0.log` is
the newest, rolled over at 10 MB, 20 files kept. Besides everything the backend logs it holds one tagged line
per event: `[AUTH]` sign-ins and account changes, `[UPLOAD]`, `[EMAIL]`, `[REPLY]`, `[CHANGE]`
(partners, templates, settings, approvals) and `[HTTP]` (requests that change something, and failed ones).
Log levels can be set as usual, e.g. `LOGGING_LEVEL_COM_NAGAD_NPSB=DEBUG`.

    java -jar build/libs/npsb-backend.jar export-log      # the log as CSV and JSON in APP_LOG_DIR/export

## Tests

    ./gradlew test             # unit and API tests
    ./gradlew contractTest     # the HTTP contract test (also part of ./gradlew check / build)

They run against an **in-memory H2 database** and never touch MySQL or send real mail: the test tasks in
`build.gradle.kts` give them their own settings and `NPSB_ENV_FILE=none`, so your `.env` is not read.

- `src/test/java/.../api`: API tests that start the real backend (`ApiTestServer`) on a free port: health
  probes, sign-in, no self sign-up, built-in admin only managing accounts, setting a password,
  forgot-password code, the RRN check across uploads, upload type and size limits, approvals and partners.
- `src/test/java/.../contract`: a black-box HTTP contract test. It drives a fresh backend only over HTTP
  through a 463-step scenario covering every endpoint (sign-in with emailed codes, users, partners,
  templates and approvals, uploads, preparing, sending and following up emails, settings, and the error
  answers 401/403/404/405/307/409/413/422/429/502, CORS) and compares every answer (status, contract headers,
  JSON body; clock values, tokens and ports normalized) with `src/test/resources/contract/api-snapshot.json`.
  It was recorded against the previous (non-Spring) backend; the Spring Boot backend answers the same.
  A difference fails the test with every differing step listed (`build/contract/contract-report.txt`).
  After an intended API change, record it again: `./gradlew contractTest -PcontractRecord=true`.
  Mail goes to a small SMTP server inside the test. The new run is written to
  `build/contract/api-snapshot.actual.json`.

## Tools (same as the Python scripts)

    # local test mail server on localhost:1025, saves every email to sent_emails/*.html
    java -jar build/libs/npsb-backend.jar test-mail-server

    # when mail is broken and nobody can get a sign-in code:
    java -jar build/libs/npsb-backend.jar recover mail             # show / fix the mail settings
    java -jar build/libs/npsb-backend.jar recover code USERNAME    # one-time sign-in code made on the server

## Code layout (`src/main/java/com/nagad/npsb/`)

| Package | What's in it |
|---------|--------------|
| `NpsbApplication` | Spring Boot entry point (+ the `recover`, `test-mail-server`, `export-log` commands) |
| `config` | settings (`NpsbProperties`, `LiveSettings`, `.env` support), data source and table set-up (`SchemaInitializer`), Spring Security, filters, OpenAPI, startup tasks |
| `controller` | REST controllers (`@RestController`), one per area |
| `service` | business logic (`@Service`), the background jobs (`FollowupService`, `Inbox`), mail (`EmailService`) |
| `dto` | request bodies (typed records, validated with the FastAPI-compatible `web.Body` reader) |
| `entity`, `repository` | JPA entities and Spring Data repositories, one per table |
| `security` | JWT access tokens (`JwtService`), the authentication filter, `CurrentUser` |
| `web` | error handling (`GlobalExceptionHandler`), CORS, request log, uploads, cookies, background tasks |
| `util` | helpers kept from the Python port (`Py`, `PyJson`, `Crypto`, `AppLog`) |
| `cli` | `RecoverCommand`, `TestMailServer` |

Notes for working on it:

- Errors keep the FastAPI format the frontend expects: throw `ApiException(status, detail)` for
  `{"detail": ...}`; invalid input gives 422 with pydantic-style `[{type, loc, msg, input}]` details
  (`web.Body`, `web.QueryParams`, `GlobalExceptionHandler`).
- Endpoints check the sign-in themselves (`currentUser.require()` / `requireAdmin()`), so each keeps its exact
  401 / 403 message and the order of its checks.
- One JPA persistence context per request; changes are written on commit or an explicit flush
  (`flushMode=COMMIT`). Where a request must keep some changes even when it then fails (e.g. a failed sign-in
  counter), the service commits that part in its own `TransactionTemplate` block first.
- The tables are created and upgraded by `SchemaInitializer` (Hibernate never changes the schema), so the
  Python and Java backends can share one database.

## What maps to what

| Python (app/)        | Java (src/main/java/com/nagad/npsb/)        |
|----------------------|---------------------------------------------|
| main.py              | controller/* + service/* (Partner, Template, Upload, Draft, Sending, SentEmail, Followup, Reply, Activity...), NpsbApplication |
| auth.py / users.py   | controller/AuthController, controller/UserController, service/AuthService, service/auth/UserAdminService |
| answers.py           | controller/AnswerController, service/AnswerService |
| models.py, database.py | entity/*, repository/*, config/SchemaInitializer, config/DataSourceConfig |
| config.py            | config/NpsbProperties, config/LiveSettings, application.yml |
| settings.py, server_settings.py | service/Settings, service/ServerSettings (+ EmailSettingsService, SystemSettingsService) |
| email_service.py     | service/EmailService |
| excel_parser.py      | service/ExcelParser (+ DateParse, Xlsx) |
| inbox.py             | service/Inbox |
| approvals.py, delivery.py, email_check.py, seed.py | service/* (same names) |
| recover.py, test_mail_server.py | cli/RecoverCommand, cli/TestMailServer |

Background jobs (automatic follow-ups, mailbox check) run on Spring's task scheduler inside the server,
as before; their intervals are read from the System Settings each time.
