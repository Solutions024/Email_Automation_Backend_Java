package com.nagad.npsb.controller;

import com.nagad.npsb.config.LiveSettings;
import com.nagad.npsb.util.Py;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Kubernetes health probes. No sign-in needed; each answers 200 when healthy, 503 when not, with a small JSON body.
 * (Spring Boot's own probes are at /actuator/health/liveness and /actuator/health/readiness as well.)
 *
 *   GET /startupz  200 once startup has finished: database tables ready, settings loaded, jobs started.
 *   GET /livez     200 while the process can answer requests. Checks nothing outside the process,
 *                  so a database or mail server outage never gets the pod restarted.
 *   GET /readyz    200 when started, not shutting down, and the database answers (checked with a
 *                  1.5 s limit, result kept for 2 s). The mail server is reported but never fails it:
 *                  emails wait and are retried, the portal still works.
 */
@RestController
@Tag(name = "health", description = "Health probes")
public class HealthController {
    private static final long DB_TIMEOUT_MS = 1500, DB_CACHE_MS = 2000;

    private final DataSource dataSource;
    private final LiveSettings live;

    private volatile boolean started = false;
    private volatile boolean stopping = false;
    private final ExecutorService dbCheck = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "health-db-check");
        t.setDaemon(true);
        return t;
    });
    private volatile long dbCheckedAt = 0;
    private volatile String dbProblem = "not checked yet";

    public HealthController(DataSource dataSource, LiveSettings live) {
        this.dataSource = dataSource;
        this.live = live;
    }

    /** Startup finished (StartupTasks): the database is ready and the jobs are running. */
    @EventListener
    public void onReadiness(AvailabilityChangeEvent<ReadinessState> event) {
        if (event.getState() == ReadinessState.ACCEPTING_TRAFFIC) started = true;
    }

    /** Shutting down: readiness fails so no new traffic is sent here. */
    @EventListener(ContextClosedEvent.class)
    public void onShutdown() {
        stopping = true;
    }

    @GetMapping("/startupz")
    @Operation(summary = "Startup probe: 200 once the backend has finished starting.")
    public ResponseEntity<Map<String, Object>> startupz() {
        if (!started) return ResponseEntity.status(503).body(Py.map("status", "starting"));
        return ResponseEntity.ok(Py.map("status", "ok"));
    }

    @GetMapping("/livez")
    @Operation(summary = "Liveness probe: 200 while the process answers (no outside checks).")
    public Map<String, Object> livez() {
        return Py.map("status", "ok");
    }

    @GetMapping("/readyz")
    @Operation(summary = "Readiness probe: 200 when started and the database answers.")
    public ResponseEntity<Map<String, Object>> readyz() {
        if (!started) return ResponseEntity.status(503).body(Py.map("status", "starting"));
        if (stopping) return ResponseEntity.status(503).body(Py.map("status", "stopping"));
        String db = databaseProblem();
        Map<String, Object> body = Py.map("status", db == null ? "ok" : "unavailable",
                "database", db == null ? "ok" : db,
                "mail", live.gmail() && Py.isEmpty(live.smtpPassword()) ? "not set up" : "configured");
        return db == null ? ResponseEntity.ok(body) : ResponseEntity.status(503).body(body);
    }

    /** null when the database answers within DB_TIMEOUT_MS, else why not. Cached for DB_CACHE_MS. */
    private String databaseProblem() {
        long now = System.currentTimeMillis();
        if (now - dbCheckedAt < DB_CACHE_MS) return dbProblem;
        synchronized (this) {
            if (System.currentTimeMillis() - dbCheckedAt < DB_CACHE_MS) return dbProblem;
            String problem;
            Future<String> check = dbCheck.submit(() -> {
                try (Connection c = dataSource.getConnection()) {
                    return c.isValid(1) ? null : "the database did not answer";
                }
            });
            try {
                problem = check.get(DB_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                check.cancel(true);
                problem = "the database did not answer within " + DB_TIMEOUT_MS + " ms";
            } catch (Exception e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                problem = "database error: " + cause.getMessage();
            }
            dbProblem = problem;
            dbCheckedAt = System.currentTimeMillis();
            return problem;
        }
    }
}
