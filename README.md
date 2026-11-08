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

Needs Docker Desktop (running). No JDK, Maven or MySQL install is needed: Docker builds the jar and runs
its own MySQL.

### Quick start (backend + its own MySQL) — recommended

Run these in PowerShell (or any terminal) **in this folder** (the one with `docker-compose.yml`):

```powershell
# 1. Settings: once. Then edit .env: set DB_PASSWORD (any non-empty value, it becomes the MySQL
#    root password), JWT_SECRET and the mail settings.
copy .env.example .env

# 2. Build and start (first run takes a few minutes; logs stay in the terminal, Ctrl+C stops)
docker compose up --build
```

A clean start ends like this:

    INFO: Application log: /app/logs
    INFO: NPSB Dispute Email Automation started
    INFO: Listening on http://0.0.0.0:8000

Then open http://localhost:8000/docs (API docs). Health check: http://localhost:8000/livez.
Sign in with the built-in admin (`admin` / `Admin@1234`) and change its password.

Everyday commands (same folder):

| What                                   | Command                                   |
|----------------------------------------|-------------------------------------------|
| Start in the background                | `docker compose up -d --build`            |
| Follow the backend log                 | `docker compose logs -f backend`          |
| Show status (both should be `healthy`) | `docker compose ps`                       |
| Restart after a code or `.env` change  | `docker compose up -d --build`            |
| Stop                                   | `docker compose down`                     |
| Stop **and delete the database**       | `docker compose down -v`                  |

Notes:
- The MySQL container's root password is the `DB_PASSWORD` from `.env`. It is set when the database is
  first created; to change it later, change it in MySQL as well (or start fresh with `docker compose down -v`).
- The database is kept in the `mysql-data` volume, so it survives restarts.
- MySQL Workbench from the computer: host `127.0.0.1`, port `3307`, user `root`, the `DB_PASSWORD` from
  `.env`. (Port 3306 is left free for a MySQL installed on the computer.)
- Ports used: `8000` (backend) and `3307` (MySQL). Stop anything else on them first.

### Alternative: `docker run` for the backend, with the MySQL container

```powershell
docker compose up -d mysql                       # once: the MySQL container (port 3307, password from .env)
docker build -t npsb-backend .
docker run --rm -p 8000:8000 --env-file .env -e HOST=0.0.0.0 -e DB_PORT=3307 npsb-backend
```

`DB_HOST=localhost` from `.env` is used as `host.docker.internal` inside the container, so this reaches the
MySQL container through port 3307. Stop MySQL afterwards with `docker compose down`.

### Alternative: only the backend image, with the MySQL installed on the computer

```powershell
docker build -t npsb-backend .
docker run --rm -p 8000:8000 --env-file .env --add-host host.docker.internal:host-gateway npsb-backend
```

Here `DB_USER` / `DB_PASSWORD` in `.env` must be the real login of the computer's MySQL (the one that works
in MySQL Workbench). The same `.env` works both directly on the computer and in the container: inside a
container, `HOST=127.0.0.1` becomes `0.0.0.0` and `DB_HOST=localhost` becomes `host.docker.internal`
(a container's "localhost" is the container itself).

### Troubleshooting

| Message at start                                   | Fix                                                        |
|----------------------------------------------------|------------------------------------------------------------|
| `MySQL refused the sign-in ... Access denied`      | `DB_USER` / `DB_PASSWORD` in `.env` are wrong for that MySQL. With compose and an old volume: `docker compose down -v`, then start again. |
| `Cannot reach MySQL ...`                           | MySQL is not running, or `DB_HOST` / `DB_PORT` are wrong. The backend retries first (`DB_CONNECT_ATTEMPTS`, default 10, 3 s apart). |
| `Port 8000 ... is already in use` / `port is already allocated` | Another backend or container uses the port: `docker ps`, then stop it. |
| `request returned 500 Internal Server Error ... dockerDesktopLinuxEngine` | Docker Desktop is not (fully) started. Start it and wait until it says *Engine running*. |

### About the image

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
