package com.nagad.npsb.api;

import com.nagad.npsb.NpsbApplication;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The optional context path (SERVER_CONTEXT_PATH; the default is none, everything at the root): when set, every
 * route, the health probes and the API docs are below it, the sign-in cookie is sent back only to its /api/auth,
 * and redirects stay below it. Runs its own backend on its own in-memory database, the API only.
 */
class ContextPathTest {
    private static final String PREFIX = "/email-automation-backend";
    private static final HttpClient HTTP = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    private static ConfigurableApplicationContext context;
    private static String host;

    @BeforeAll
    static void start() {
        context = new SpringApplicationBuilder(NpsbApplication.class)
                .properties("npsb.jobs.enabled=false")
                .run("--server.port=0", "--server.address=127.0.0.1", "--server.servlet.context-path=" + PREFIX,
                        "--npsb.frontend-dir=",
                        "--npsb.db.url=jdbc:h2:mem:npsbcontextpath;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        host = "http://127.0.0.1:" + context.getEnvironment().getProperty("local.server.port");
    }

    @AfterAll
    static void stop() {
        if (context != null) context.close();
    }

    private static HttpResponse<String> send(String method, String path, String json) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(host + path));
        if (json != null) {
            b.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(json));
        } else {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        }
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    @Test
    void healthProbesAndDocsAreBelowTheContextPath() throws Exception {
        for (String probe : new String[]{"/livez", "/readyz", "/startupz"}) {
            HttpResponse<String> r = send("GET", PREFIX + probe, null);
            assertEquals(200, r.statusCode(), probe + ": " + r.body());
            assertTrue(r.body().contains("\"status\":\"ok\""), r.body());
        }
        assertEquals(200, send("GET", PREFIX + "/openapi.json", null).statusCode());
        assertEquals(200, send("GET", PREFIX + "/actuator/health", null).statusCode());
    }

    @Test
    void nothingAnswersAtTheRoot() throws Exception {
        assertEquals(404, send("GET", "/livez", null).statusCode());
        assertEquals(404, send("POST", "/api/auth/login", "{\"login\":\"admin\",\"password\":\"Admin@1234\"}").statusCode());
    }

    @Test
    void signInWorksAndItsCookieIsScopedToTheContextPath() throws Exception {
        HttpResponse<String> r = send("POST", PREFIX + "/api/auth/login", "{\"login\":\"admin\",\"password\":\"Admin@1234\"}");
        assertEquals(200, r.statusCode(), r.body());
        assertTrue(r.body().contains("access_token"), r.body());
        String cookie = r.headers().allValues("Set-Cookie").stream().filter(c -> c.startsWith("npsb_refresh=")).findFirst()
                .orElseThrow(() -> new AssertionError("no refresh cookie: " + r.headers().map()));
        assertTrue(cookie.contains("Path=" + PREFIX + "/api/auth"), cookie);
    }

    @Test
    void trailingSlashRedirectStaysBelowTheContextPath() throws Exception {
        HttpResponse<String> r = send("GET", PREFIX + "/api/sent-emails/?sort=oldest", null);
        assertEquals(307, r.statusCode());
        assertEquals(PREFIX + "/api/sent-emails?sort=oldest", r.headers().firstValue("Location").orElse(""));
    }
}
