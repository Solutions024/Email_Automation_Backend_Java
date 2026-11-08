# NPSB Dispute Email Automation — backend (Java)

Java 17 port of the FastAPI backend. Same REST API (paths, request/response JSON, status codes and
error messages), same MySQL tables and columns, so the existing React frontend and an existing
database work unchanged. Passwords, JWT sessions and the encrypted System Settings secrets stored by
the Python version stay valid (same hashing, token and encryption formats).

## Build

Needs JDK 17+ and Maven 3.8+.

    mvn package

This makes one runnable jar: `target/npsb-backend.jar`.

## Run

1. Copy `.env.example` to `.env` (next to where you start the jar) and fill in the database,
   mail server and `JWT_SECRET`. There is no self sign-up: the built-in admin (username `admin`,
   password `Admin@1234`) is created on first start and creates every other account. Change its
   password on My Profile straight away.
2. Start MySQL. The database and all tables are created automatically on first start; older
   databases get missing columns added, exactly like the Python version did.
3. Start the API:

       java -jar target/npsb-backend.jar                  # host/port from .env (default 127.0.0.1:8000)
       java -jar target/npsb-backend.jar --port 8000 --host 0.0.0.0

API docs: http://localhost:8000/docs (Swagger UI) and /openapi.json.

## Docker

Recommended: the backend together with its own MySQL (`docker-compose.yml`). Copy `.env.example` to `.env`
first; the MySQL container's root password is the `DB_PASSWORD` from `.env`.

    docker compose up --build          # logs in the terminal, Ctrl+C stops
    docker compose up -d --build       # in the background; docker compose logs -f backend
    docker compose down                # stop (add -v to also delete the database)

A clean start looks like this:

    INFO: Application log: /app/logs
    INFO: NPSB Dispute Email Automation started
    INFO: Listening on http://0.0.0.0:8000

The database lives in the `mysql-data` volume. From MySQL Workbench on the computer: host `127.0.0.1`,
port `3307` (3306 is left free for a MySQL installed on the computer).

Only the image, against a MySQL that runs on the computer itself:

    docker build -t npsb-backend .
    docker run -p 8000:8000 --env-file .env --add-host host.docker.internal:host-gateway npsb-backend

The same `.env` works on the computer and in the container: inside a container, `HOST=127.0.0.1` becomes
`0.0.0.0` and `DB_HOST=localhost` becomes `host.docker.internal` (a container's "localhost" is the container
itself). `DB_USER` / `DB_PASSWORD` must be valid for that MySQL. If MySQL is still starting, the backend
retries the connection (`DB_CONNECT_ATTEMPTS`, default 10, 3 s apart), and a failed connection is reported
as one clear line with the likely fix instead of a stack trace.

Multi-stage build (Maven on Temurin 21, then a Temurin 21 JRE runtime) that follows the Container Image
Build Security Standard (NGD-CTR-001): non-root user (uid 10001), no secrets in the image, `.dockerignore`
for `.env` and keys, setuid/setgid bits removed, `HEALTHCHECK` on `/livez`. All settings come from
environment variables at run time (see `.env.example`); the image sets `HOST=0.0.0.0`, `PORT=8000` and
`APP_LOG_DIR=/app/logs` (mount a volume there to keep the log). Use `/startupz`, `/livez` and `/readyz`
as Kubernetes probes.

## Tests

    mvn test

JUnit 5 tests in `src/test/java`: unit tests (upload parsing, the application log) and API tests that
start the real backend (routes, sign-in, SQL) on a free port against an **in-memory H2 database**.
They never touch MySQL or send mail: `pom.xml` (surefire) gives them their own settings and
`NPSB_ENV_FILE=none`, so your `.env` is not read. Covered: health probes, sign-in, no self sign-up,
built-in admin only managing accounts, setting a password, forgot-password code, the RRN check
across uploads, upload type and size limits, approvals and partner changes.

## Tools (same as the Python scripts)

    # local test mail server on localhost:1025, saves every email to sent_emails/*.html
    java -jar target/npsb-backend.jar test-mail-server

    # when mail is broken and nobody can get a sign-in code:
    java -jar target/npsb-backend.jar recover mail             # show / fix the mail settings
    java -jar target/npsb-backend.jar recover code USERNAME    # one-time sign-in code made on the server

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
