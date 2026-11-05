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
   mail server, `JWT_SECRET` and `ADMIN_SIGNUP_CODE`.
2. Start MySQL. The database and all tables are created automatically on first start; older
   databases get missing columns added, exactly like the Python version did.
3. Start the API:

       java -jar target/npsb-backend.jar                  # host/port from .env (default 127.0.0.1:8000)
       java -jar target/npsb-backend.jar --port 8000 --host 0.0.0.0

API docs: http://localhost:8000/docs (Swagger UI) and /openapi.json.

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
| inbox.py, forwarding.py | service/Inbox.java, service/Forwarding.java |
| approvals.py, alerts.py, delivery.py, email_check.py, seed.py | service/* (same names) |

Background jobs (automatic follow-ups, mailbox check, forwarding replies) run as threads inside the
server, as before.
