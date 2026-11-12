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
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The whole app on one port, everything at the root (FrontendConfig): /, /login and any page address open the app
 * (a built frontend in FRONTEND_DIR), its built files are served, and /api/..., /livez, /docs stay the backend's:
 * the app never answers in place of the API (401, 404, 307, 405 as before).
 * Runs its own backend on its own in-memory database with a tiny frontend build.
 */
class FrontendServingTest {
    private static final HttpClient HTTP = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    private static final String APP = "<title>NPSB app</title>";
    private static ConfigurableApplicationContext context;
    private static String host;
    private static Path frontend;

    @BeforeAll
    static void start() throws Exception {
        frontend = Files.createTempDirectory("npsb-frontend");
        Files.createDirectories(frontend.resolve("assets"));
        Files.writeString(frontend.resolve("index.html"), "<!doctype html>" + APP + "<script src=\"/assets/app.js\"></script>");
        Files.writeString(frontend.resolve("assets/app.js"), "console.log('app');");
        context = new SpringApplicationBuilder(NpsbApplication.class)
                .properties("npsb.jobs.enabled=false")
                .run("--server.port=0", "--server.address=127.0.0.1", "--server.servlet.context-path=",
                        "--npsb.frontend-dir=" + frontend,
                        "--npsb.db.url=jdbc:h2:mem:npsbfrontend;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        host = "http://127.0.0.1:" + context.getEnvironment().getProperty("local.server.port");
    }

    @AfterAll
    static void stop() {
        if (context != null) context.close();
    }

    private static HttpResponse<String> send(String method, String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(host + path)).method(method, HttpRequest.BodyPublishers.noBody()).build();
        return HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    @Test
    void pagesOpenTheApp() throws Exception {
        for (String page : new String[]{"/", "/login", "/login/", "/some/page"}) {
            HttpResponse<String> r = send("GET", page);
            assertEquals(200, r.statusCode(), page);
            assertTrue(r.body().contains(APP), page + ": " + r.body());
            assertTrue(r.headers().firstValue("Content-Type").orElse("").startsWith("text/html"), page);
        }
    }

    @Test
    void builtFilesAreServed() throws Exception {
        HttpResponse<String> js = send("GET", "/assets/app.js");
        assertEquals(200, js.statusCode());
        assertEquals("console.log('app');", js.body());
        assertTrue(js.headers().firstValue("Content-Type").orElse("").contains("javascript"));
        assertEquals(404, send("GET", "/assets/missing.js").statusCode());
    }

    @Test
    void theApiStaysTheApi() throws Exception {
        HttpResponse<String> signedOut = send("GET", "/api/partners");
        assertEquals(401, signedOut.statusCode());
        assertFalse(signedOut.body().contains(APP));
        HttpResponse<String> unknown = send("GET", "/api/does-not-exist");
        assertEquals(404, unknown.statusCode());
        assertEquals("{\"detail\":\"Not Found\"}", unknown.body());
        assertEquals(307, send("GET", "/api/partners/").statusCode());
        assertEquals(405, send("DELETE", "/api/partners").statusCode());
        assertEquals(405, send("POST", "/login").statusCode());
    }

    @Test
    void healthProbesAndDocsAreTheBackends() throws Exception {
        HttpResponse<String> live = send("GET", "/livez");
        assertEquals(200, live.statusCode());
        assertTrue(live.body().contains("\"status\":\"ok\""), live.body());
        assertFalse(live.body().contains(APP));
        assertEquals(200, send("GET", "/openapi.json").statusCode());
    }

    @Test
    void filesOutsideTheBuildAreNeverServed() throws Exception {
        Files.writeString(frontend.getParent().resolve("npsb-secret.txt"), "secret");
        for (String path : new String[]{"/../npsb-secret.txt", "/%2e%2e/npsb-secret.txt", "/assets/..%2f..%2fnpsb-secret.txt"}) {
            assertFalse(send("GET", path).body().contains("secret"), path);
        }
    }
}
