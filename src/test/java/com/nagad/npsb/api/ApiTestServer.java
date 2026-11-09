package com.nagad.npsb.api;

import com.nagad.npsb.NpsbApplication;
import com.nagad.npsb.entity.User;
import com.nagad.npsb.repository.UserRepository;
import com.nagad.npsb.security.JwtService;
import com.nagad.npsb.util.PyJson;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The real backend (Spring Boot: routes, security, JPA) on a free local port, against the in-memory H2 database the
 * test run is started with (DATABASE_URL=jdbc:h2:mem:...; see build.gradle.kts). Never touches MySQL.
 * Started once per test run and shared by every test class.
 */
public final class ApiTestServer {
    private ApiTestServer() {}

    private static String base;
    private static ConfigurableApplicationContext context;
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    public static synchronized String url() {
        if (base != null) return base;
        String database = System.getenv("DATABASE_URL");
        if (database == null || !database.startsWith("jdbc:h2:mem:")) {
            throw new IllegalStateException("Tests must run against an in-memory H2 database (DATABASE_URL=jdbc:h2:mem:...), not "
                    + (database == null || database.isEmpty() ? "the MySQL database in .env" : database));
        }
        context = new SpringApplicationBuilder(NpsbApplication.class)
                // no background jobs (automatic follow-ups, mailbox check), as before
                .properties("server.port=0", "server.address=127.0.0.1", "npsb.jobs.enabled=false")
                .run();
        base = "http://127.0.0.1:" + context.getEnvironment().getProperty("local.server.port");
        return base;
    }

    /** A bean of the running backend (repositories, services...). */
    public static <T> T bean(Class<T> type) {
        url();
        return context.getBean(type);
    }

    /** Run database work in its own transaction (like the old "try (Db db = Db.open()) { ... db.commit(); }"). */
    public static <T> T inTransaction(Supplier<T> work) {
        return bean(TransactionTemplate.class).execute(status -> work.get());
    }

    /** A signed-in session for this user (the real access token the login endpoint would give). */
    public static String tokenFor(String username) {
        User u = bean(UserRepository.class).findByUsername(username)
                .orElseThrow(() -> new IllegalStateException("No user " + username));
        return bean(JwtService.class).createAccessToken(u);
    }

    public record Response(int status, String body) {
        @SuppressWarnings("unchecked")
        public Map<String, Object> json() {
            try {
                return PyJson.MAPPER.readValue(body, Map.class);
            } catch (Exception e) {
                throw new AssertionError("Not a JSON object (" + status + "): " + body);
            }
        }
    }

    public static Response send(String method, String path, String token, Object body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url() + path));
        if (token != null) b.header("Authorization", "Bearer " + token);
        if (body != null) {
            b.header("Content-Type", "application/json");
            b.method(method, HttpRequest.BodyPublishers.ofString(PyJson.MAPPER.writeValueAsString(body)));
        } else {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        }
        HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new Response(r.statusCode(), r.body());
    }

    /** A multipart/form-data upload of one file in the field "file". */
    public static Response upload(String path, String token, String filename, byte[] content) throws Exception {
        String boundary = "----npsbtest" + System.nanoTime();
        byte[] head = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + filename
                + "\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] all = new byte[head.length + content.length + tail.length];
        System.arraycopy(head, 0, all, 0, head.length);
        System.arraycopy(content, 0, all, head.length, content.length);
        System.arraycopy(tail, 0, all, head.length + content.length, tail.length);
        HttpRequest req = HttpRequest.newBuilder(URI.create(url() + path))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(all)).build();
        HttpResponse<String> r = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new Response(r.statusCode(), r.body());
    }
}
