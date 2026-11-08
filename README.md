# NPSB Dispute Email Automation — backend (Java)

Java 17 port of the FastAPI backend. Same REST API (paths, request/response JSON, status codes and
error messages), same MySQL tables and columns, so the existing React frontend and an existing
database work unchanged. Passwords, JWT sessions and the encrypted System Settings secrets stored by
the Python version stay valid (same hashing, token and encryption formats).

## Build and run with Docker (recommended)

**You only need [Docker Desktop](https://www.docker.com/products/docker-desktop/)**, started and showing
*Engine running*. No JDK, Maven or MySQL install is needed: one command builds the backend image from the
source code and starts it together with its own MySQL database.

Run every command below in PowerShell (Windows) or a terminal (macOS / Linux).

### Step 1 — Get the code (once)

```powershell
git clone https://github.com/Solutions024/Email_Automation_Backend_Java.git
cd Email_Automation_Backend_Java
```

All further commands are run **in this folder** (the one that contains `docker-compose.yml`).

### Step 2 — Create the settings file `.env` (once)

```powershell
copy .env.example .env          # macOS / Linux: cp .env.example .env
```

Open `.env` in an editor and set at least:

| Setting           | What to put                                                                  |
|-------------------|------------------------------------------------------------------------------|
| `DB_PASSWORD`     | **Required, not empty.** Any password; it becomes the MySQL root password.    |
| `JWT_SECRET`      | A long random string (otherwise everyone is signed out on every restart).    |
| `GMAIL_ADDRESS`, `GMAIL_APP_PASSWORD` | The mailbox that sends emails and reads replies (or the `SMTP_*` / `IMAP_*` settings with `MAIL_PROVIDER=smtp`). |

Leave `DB_HOST` / `DB_PORT` as they are: Docker sets them to the MySQL container itself.

### Step 3 — Build and start

```powershell
docker compose up -d --build
```

This one command does everything:

1. **builds** the backend image `npsb-backend` from the source (Maven runs inside Docker; the first build
   takes a few minutes, later builds are fast because of the cache),
2. starts the **MySQL** container and waits until it is ready (database and tables are created automatically),
3. starts the **backend** container on port 8000.

`-d` runs the containers in the background, so the terminal shows only the build output and ends with:

    ✔ Image npsb-backend               Built
    ✔ Container npsb-backend-mysql-1   Healthy
    ✔ Container npsb-backend-backend-1 Started

That is a successful start. The application log is not shown here; see Step 4.

### Step 4 — Check that it is running

```powershell
docker compose ps
```

Both containers must show **`Up ... (healthy)`** (the backend needs up to about 30 seconds to become healthy):

    NAME                     STATUS                    PORTS
    npsb-backend-backend-1   Up 53 seconds (healthy)   0.0.0.0:8000->8000/tcp
    npsb-backend-mysql-1     Up 4 minutes (healthy)    0.0.0.0:3307->3306/tcp

See the backend log (Ctrl+C only stops watching the log, not the backend):

```powershell
docker compose logs -f backend
```

A clean start looks like this:

    INFO: Application log: /app/logs
    INFO: NPSB Dispute Email Automation started
    INFO: Listening on http://0.0.0.0:8000

### Step 5 — Open it

- API docs (Swagger UI): http://localhost:8000/docs
- Health check: http://localhost:8000/livez (answers `200`)
- Sign in with the built-in admin: username `admin`, password `Admin@1234`. There is no self sign-up:
  this admin creates every other account. **Change its password on My Profile straight away.**

### Everyday commands

Always run them in the project folder.

| What                                          | Command                           |
|-----------------------------------------------|-----------------------------------|
| Build and start (in the background)           | `docker compose up -d --build`    |
| Build and start with the log in the terminal  | `docker compose up --build` (Ctrl+C stops everything) |
| Status (both should be `healthy`)             | `docker compose ps`               |
| Backend log                                   | `docker compose logs -f backend`  |
| Apply a code change or a `.env` change        | `docker compose up -d --build`    |
| Restart the backend only                      | `docker compose restart backend`  |
| Stop                                          | `docker compose down`             |
| Stop **and delete the database** (fresh start)| `docker compose down -v`          |

Good to know:
- The data is kept in the Docker volume `mysql-data`, so it survives `docker compose down` and reboots
  (only `docker compose down -v` deletes it).
- The MySQL root password is the `DB_PASSWORD` that was in `.env` when the database was **first** created.
  Changing `DB_PASSWORD` later does not change it in MySQL; for a fresh database use `docker compose down -v`.
- MySQL Workbench from the computer: host `127.0.0.1`, port **3307**, user `root`, password = `DB_PASSWORD`.
  (Port 3306 is left free for a MySQL installed on the computer.)
- Ports used: **8000** (backend) and **3307** (MySQL). Nothing else may use them.
- After a reboot the containers start again by themselves once Docker Desktop is running
  (`restart: unless-stopped`), unless they were stopped with `docker compose down`.
- Tools inside the container, e.g. when mail is broken (see *Tools* below):
  `docker compose exec backend java -jar /app/app.jar recover mail`

### Troubleshooting

| What you see                                       | Fix                                                        |
|----------------------------------------------------|------------------------------------------------------------|
| `request returned 500 Internal Server Error ... dockerDesktopLinuxEngine` | Docker Desktop is not (fully) started. Start it, wait for *Engine running*, run the command again. |
| `Bind for 0.0.0.0:8000 failed: port is already allocated` | Something else uses port 8000 (often an older backend container). `docker ps` shows it; stop it with `docker stop <name>` (or `docker compose down` in its folder), then start again. |
| `docker compose ps` shows the backend `Restarting` or `Exited` | Read the reason: `docker compose logs backend`. |
| `MySQL refused the sign-in ... Access denied`      | `DB_PASSWORD` in `.env` differs from the one the database was created with. Put the old one back, or start fresh with `docker compose down -v` (deletes the data). |
| `Cannot reach MySQL ...`                           | MySQL is not up (yet). The backend retries (`DB_CONNECT_ATTEMPTS`, default 10, 3 s apart); check `docker compose ps` / `docker compose logs mysql`. |
| MySQL container exits right away                   | `DB_PASSWORD` in `.env` is empty. Set it, then `docker compose up -d --build`. |

### Other ways to run the image (advanced)

**`docker run` for the backend, with the MySQL container from `docker-compose.yml`:**

```powershell
docker compose up -d mysql
docker build -t npsb-backend .
docker run --rm -p 8000:8000 --env-file .env -e HOST=0.0.0.0 -e DB_PORT=3307 npsb-backend
```

Inside a container `DB_HOST=localhost` is used as `host.docker.internal` (the computer), so this reaches the
MySQL container through port 3307. Ctrl+C stops the backend; `docker compose down` stops MySQL.

**Only the backend image, with a MySQL installed on the computer** (port 3306):

```powershell
docker build -t npsb-backend .
docker run --rm -p 8000:8000 --env-file .env --add-host host.docker.internal:host-gateway npsb-backend
```

Here `DB_USER` / `DB_PASSWORD` in `.env` must be the real login of that MySQL (the one that works in
MySQL Workbench). The same `.env` works on the computer and in the container: inside a container
`HOST=127.0.0.1` becomes `0.0.0.0` and `DB_HOST=localhost` becomes `host.docker.internal`.

### About the image

Multi-stage build (Maven on Temurin 21, then a Temurin 21 JRE runtime) that follows the Container Image
Build Security Standard (NGD-CTR-001): non-root user (uid 10001), no secrets in the image, `.dockerignore`
for `.env` and keys, setuid/setgid bits removed, `HEALTHCHECK` on `/livez`. All settings come from
environment variables at run time (see `.env.example`); the image sets `HOST=0.0.0.0`, `PORT=8000` and
`APP_LOG_DIR=/app/logs` (`docker-compose.yml` keeps it in the `backend-logs` volume). Use `/startupz`,
`/livez` and `/readyz` as Kubernetes probes.

## Build and run without Docker

Needs JDK 17+, Maven 3.8+ and a MySQL server.

1. Build the jar:

       mvn package

   This makes one runnable jar: `target/npsb-backend.jar`.
2. Copy `.env.example` to `.env` (next to where you start the jar) and fill in the database
   (`DB_HOST`, `DB_PORT`, `DB_USER`, `DB_PASSWORD` of your MySQL), mail server and `JWT_SECRET`.
3. Start MySQL. The database and all tables are created automatically on first start; older
   databases get missing columns added, exactly like the Python version did.
4. Start the API:

       java -jar target/npsb-backend.jar                  # host/port from .env (default 127.0.0.1:8000)
       java -jar target/npsb-backend.jar --port 8000 --host 0.0.0.0

API docs: http://localhost:8000/docs (Swagger UI) and /openapi.json. Sign in as `admin` / `Admin@1234`
and change the password.

## Tests

    mvn test

JUnit 5 tests in `src/test/java`: unit tests (upload parsing, the application log) and API tests that
start the real backend (routes, sign-in, SQL) on a free port against an **in-memory H2 database**.
They never touch MySQL or send mail: `pom.xml` (surefire) gives them their own settings and
`NPSB_ENV_FILE=none`, so your `.env` is not read. Covered: health probes, sign-in, no self sign-up,
built-in admin only managing accounts, setting a password, forgot-password code, the RRN check
across uploads, upload type and size limits, approvals and partner changes.

Without a local JDK, run them in Docker (PowerShell, in the project folder):

    docker run --rm -v "${PWD}:/src" -w /src maven:3.9.9-eclipse-temurin-21 mvn -B test

## Tools (same as the Python scripts)

    # local test mail server on localhost:1025, saves every email to sent_emails/*.html
    java -jar target/npsb-backend.jar test-mail-server

    # when mail is broken and nobody can get a sign-in code:
    java -jar target/npsb-backend.jar recover mail             # show / fix the mail settings
    java -jar target/npsb-backend.jar recover code USERNAME    # one-time sign-in code made on the server

With Docker, run them in the backend container instead, e.g.
`docker compose exec backend java -jar /app/app.jar recover code USERNAME`.

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
