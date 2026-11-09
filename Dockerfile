# syntax=docker/dockerfile:1
# NPSB Dispute Email Automation - backend image (Spring Boot, built with Gradle).
# Follows NGD-CTR-001 (Container Image Build Security Standard); control IDs are noted as CIB-xx.
#
#   docker compose up --build      (recommended: backend + its own MySQL, see docker-compose.yml and README.md)
#
#   or only this image, with the MySQL installed on the computer:
#   docker build -t npsb-backend .
#   docker run --rm -p 8000:8000 --env-file .env -e DB_HOST=host.docker.internal \
#       --add-host host.docker.internal:host-gateway npsb-backend
#
# All settings (database, mail, JWT_SECRET, ...) come from environment variables at run time, never from the
# image (CIB-06). See .env.example for the full list. NPSB_ENV_FILE=none: no .env file is read inside the image.

# ============================================================
# Stage 1: Build (CIB-04 multi-stage; Gradle and the JDK stay out of the runtime image)
# ============================================================
# CIB-01 / CIB-02: official Eclipse Temurin JDK, explicit version, no "latest". Gradle comes from the wrapper.
FROM eclipse-temurin:21-jdk AS builder

WORKDIR /build

# Build scripts and the wrapper first, so Gradle and the dependencies are cached while only the source changes
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle/ gradle/
RUN sed -i 's/\r$//' gradlew \
    && chmod +x gradlew \
    && ./gradlew --no-daemon -q dependencies --configuration runtimeClasspath > /dev/null

COPY src/main/ src/main/

# One runnable Spring Boot jar: build/libs/npsb-backend.jar. Tests run with `./gradlew build` outside the image build.
RUN ./gradlew --no-daemon -q bootJar \
    && test -f build/libs/npsb-backend.jar

# ============================================================
# Stage 2: Runtime
# ============================================================
# CIB-01 / CIB-02 / CIB-03: JRE only (no compiler, no Gradle)
FROM eclipse-temurin:21-jre

LABEL org.opencontainers.image.title="NPSB Dispute Email Automation - Backend" \
      org.opencontainers.image.description="REST API for NPSB dispute email handling (Spring Boot, Java 21 runtime)" \
      org.opencontainers.image.source="https://github.com/Solutions024/Email_Automation_Backend_Java"

# Only non-secret defaults. HOST must be 0.0.0.0 inside a container (the app's default is 127.0.0.1).
ENV HOME=/home/appuser \
    TZ=Asia/Dhaka \
    HOST=0.0.0.0 \
    PORT=8000 \
    APP_LOG_DIR=/app/logs \
    NPSB_ENV_FILE=none

# CIB-05: dedicated non-root user and group (fixed IDs for runAsUser / volume permissions)
# CIB-14: only the log folder is writable by the app; the jar stays root-owned and read-only
# CIB-15: drop setuid/setgid bits (su, passwd, mount, ...) the application never needs
# CIB-08 / CIB-09: no packages installed (curl for the health check is already in the base image)
RUN groupadd --system --gid 10001 appgroup \
    && useradd --system --uid 10001 --gid appgroup \
       --home-dir /home/appuser --create-home --shell /usr/sbin/nologin appuser \
    && mkdir -p /app/logs \
    && chown appuser:appgroup /app/logs \
    && chmod 750 /app/logs \
    && find / -xdev -type f -perm /6000 -exec chmod a-s {} +

# CIB-16: dedicated working directory
WORKDIR /app

# CIB-12: COPY, not ADD. Only the packaged application leaves the build stage.
COPY --from=builder /build/build/libs/npsb-backend.jar /app/app.jar

USER 10001:10001

EXPOSE 8000

# CIB-18: liveness probe built into the backend (/startupz, /readyz and /actuator/health/* also exist for Kubernetes)
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD curl -fsS --max-time 8 "http://127.0.0.1:${PORT}/livez" || exit 1

# CIB-17: exec form, so the JVM is PID 1 and receives SIGTERM directly
# JVM flags go here, not in JAVA_TOOL_OPTIONS (that makes the JVM print "Picked up JAVA_TOOL_OPTIONS" at start)
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/app.jar"]
