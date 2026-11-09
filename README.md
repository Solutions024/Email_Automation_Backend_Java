# NPSB Dispute Email Automation — backend (Java)

Java 17 port of the FastAPI backend. Same REST API (paths, request/response JSON, status codes and
error messages), same MySQL tables and columns, so the existing React frontend and an existing
database work unchanged. Passwords, JWT sessions and the encrypted System Settings secrets stored by
the Python version stay valid (same hashing, token and encryption formats).

## Build

Needs JDK 17+. Gradle itself comes with the project (the wrapper downloads it on first use).

    ./gradlew build          # Windows: gradlew.bat build

This runs the tests and makes one runnable jar: `build/libs/npsb-backend.jar`
(`./gradlew shadowJar` makes only the jar, without running the tests).

While developing, `./gradlew run` starts the API straight from the sources (same `.env`);
pass arguments with `--args`, e.g. `./gradlew run --args="--port 8001"`.

Dependency versions are in `gradle/libs.versions.toml`, the build in `build.gradle.kts`.

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

API docs: http://localhost:8000/docs (Swagger UI) and /openapi.json.

## Tests

    ./gradlew test

JUnit 5 tests in `src/test/java`: unit tests (upload parsing, the application log) and API tests that
start the real backend (routes, sign-in, SQL) on a free port against an **in-memory H2 database**.
They never touch MySQL or send mail: the `test` task in `build.gradle.kts` gives them their own settings and
`NPSB_ENV_FILE=none`, so your `.env` is not read. Covered: health probes, sign-in, no self sign-up,
built-in admin only managing accounts, setting a password, forgot-password code, the RRN check
across uploads, upload type and size limits, approvals and partner changes.

### HTTP contract test

    ./gradlew contractTest                          # compare with the recorded contract (also part of ./gradlew check)
    ./gradlew contractTest -PcontractRecord=true    # record it again after an intended API change

`src/test/java/com/nagad/npsb/contract` drives a fresh backend (its own in-memory database) only over HTTP
through a long scenario: sign-in with emailed codes, users, partners, templates and approvals, uploads,
preparing, sending and following up emails, settings, and the error answers (401/403/404/405/307/413/422, CORS).
Every answer (status, contract headers, JSON body with clock values, tokens and ports normalized) is kept in
`src/test/resources/contract/api-snapshot.json`; a difference fails the test with every differing step listed
(also in `build/contract/contract-report.txt`, the new run in `build/contract/api-snapshot.actual.json`).
Mail goes to a small SMTP server inside the test. The same test can be replayed against a reimplementation
of the backend: it only needs `ApiTestServer.url()` and `ApiTestServer.tokenFor(username)`.

## Tools (same as the Python scripts)

    # local test mail server on localhost:1025, saves every email to sent_emails/*.html
    java -jar build/libs/npsb-backend.jar test-mail-server

    # when mail is broken and nobody can get a sign-in code:
    java -jar build/libs/npsb-backend.jar recover mail             # show / fix the mail settings
    java -jar build/libs/npsb-backend.jar recover code USERNAME    # one-time sign-in code made on the server

## What maps to what

| Python (app/)        | Java (src/main/java/com/nagad/npsb/)        |
|----------------------|---------------------------------------------|
| main.py              | api/MainApi.java, api/MoreApi.java, Application.java |
| auth.py / users.py   | api/Auth.java / api/Users.java              |
| answers.py           | api/Answers.java                            |
| models.py, database.py | models/*, db/Database.java, db/Db.java    |
| config.py            | Config.java                                 |
| settings.py, server_settings.py | service/Settings.java, service/ServerSettings.java |
| email_service.py | service/EmailService.java |
| excel_parser.py      | service/ExcelParser.java (+ DateParse, Xlsx) |
| inbox.py | service/Inbox.java |
| approvals.py, alerts.py, delivery.py, email_check.py, seed.py | service/* (same names) |
| recover.py, test_mail_server.py | Recover.java, TestMailServer.java |

Background jobs (automatic follow-ups, mailbox check) run as threads inside the
server, as before.
