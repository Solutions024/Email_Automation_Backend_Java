package com.nagad.npsb.api;

import com.nagad.npsb.Py;
import com.nagad.npsb.db.Database;
import com.nagad.npsb.web.Req;
import com.nagad.npsb.web.Server;

import java.sql.Connection;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Kubernetes health probes. No sign-in needed; each answers 200 when healthy, 503 when not, with a small JSON body.
 *
 *   GET /startupz  (S, under 2 s)  200 once startup has finished: database tables ready, settings loaded, jobs started.
 *   GET /livez     (L, under 1 s)  200 while the process can answer requests. Checks nothing outside the process,
 *                                  so a database or mail server outage never gets the pod restarted.
 *   GET /readyz    (R, under 2 s)  200 when started, not shutting down, and the database answers (checked with a
 *                                  1.5 s limit, result kept for 2 s). The mail server is reported but never fails it:
 *                                  emails wait and are retried, the portal still works.
 */
public final class Health {
    private Health() {}

    private static volatile boolean started = false;
    private static volatile boolean stopping = false;

    /** Startup finished (Application.startup). */
    public static void markStarted() { started = true; }

    /** Shutting down: readiness fails so no new traffic is sent here. */
    public static void markStopping() { stopping = true; }

    private static final long DB_TIMEOUT_MS = 1500, DB_CACHE_MS = 2000;
    private static final ExecutorService DB_CHECK = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "health-db-check");
        t.setDaemon(true);
        return t;
    });
    private static volatile long dbCheckedAt = 0;
    private static volatile String dbProblem = "not checked yet";

    public static void routes(Server s) {
        s.get("/startupz", "Startup probe: 200 once the backend has finished starting.", Health::startupz);
        s.get("/livez", "Liveness probe: 200 while the process answers (no outside checks).", Health::livez);
        s.get("/readyz", "Readiness probe: 200 when started and the database answers.", Health::readyz);
    }

    static Object startupz(Req req) {
        if (!started) return unhealthy(req, Py.map("status", "starting"));
        return Py.map("status", "ok");
    }

    static Object livez(Req req) {
        return Py.map("status", "ok");
    }

    static Object readyz(Req req) {
        if (!started) return unhealthy(req, Py.map("status", "starting"));
        if (stopping) return unhealthy(req, Py.map("status", "stopping"));
        String db = databaseProblem();
        Map<String, Object> body = Py.map("status", db == null ? "ok" : "unavailable",
                "database", db == null ? "ok" : db,
                "mail", com.nagad.npsb.Config.gmail() && Py.isEmpty(com.nagad.npsb.Config.SMTP_PASSWORD()) ? "not set up" : "configured");
        return db == null ? body : unhealthy(req, body);
    }

    private static Object unhealthy(Req req, Map<String, Object> body) {
        req.res.status = 503;
        return body;
    }

    /** null when the database answers within DB_TIMEOUT_MS, else why not. Cached for DB_CACHE_MS. */
    private static String databaseProblem() {
        long now = System.currentTimeMillis();
        if (now - dbCheckedAt < DB_CACHE_MS) return dbProblem;
        synchronized (Health.class) {
            if (System.currentTimeMillis() - dbCheckedAt < DB_CACHE_MS) return dbProblem;
            String problem;
            Future<String> check = DB_CHECK.submit(() -> {
                try (Connection c = Database.dataSource().getConnection()) {
                    return c.isValid(1) ? null : "the database did not answer";
                }
            });
            try {
                problem = check.get(DB_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.TimeoutException e) {
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
