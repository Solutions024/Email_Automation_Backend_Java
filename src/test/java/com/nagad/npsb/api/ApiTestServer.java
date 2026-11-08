package com.nagad.npsb.api;

import com.nagad.npsb.Config;
import com.nagad.npsb.PyJson;
import com.nagad.npsb.db.Database;
import com.nagad.npsb.db.Db;
import com.nagad.npsb.models.User;
import com.nagad.npsb.service.Seed;
import com.nagad.npsb.service.ServerSettings;
import com.nagad.npsb.web.Server;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * The real backend (routes, auth, SQL) on a free local port, against the in-memory H2 database the test run
 * is started with (DATABASE_URL=jdbc:h2:mem:...; see backend/README.md "Tests"). Never touches MySQL.
 * Started once per test run and shared by every test class.
 */
public final class ApiTestServer {
    private ApiTestServer() {}

    private static String base;
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    public static synchronized String url() throws Exception {
        if (base != null) return base;
        if (!Config.DATABASE_URL.startsWith("jdbc:h2:mem:")) {
            throw new IllegalStateException("Tests must run against an in-memory H2 database (DATABASE_URL=jdbc:h2:mem:...), not "
                    + (Config.DATABASE_URL.isEmpty() ? "the MySQL database in .env" : Config.DATABASE_URL));
        }
        Database.createTables();
        try (Db db = Db.open()) {
            Seed.seedTemplates(db);
            ServerSettings.load(db);
            Auth.ensureBuiltInAdmin(db);
        }
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        Server server = new Server();
        Health.routes(server);
        Auth.routes(server);
        com.nagad.npsb.api.Users.routes(server);
        MainApi.routes(server);
        Answers.routes(server);
        server.start("127.0.0.1", port);
        Health.markStarted();
        base = "http://127.0.0.1:" + port;
        return base;
    }

    /** A signed-in session for this user (the real access token the login endpoint would give). */
    public static String tokenFor(String username) {
        try (Db db = Db.open()) {
            User u = db.first(User.class, "WHERE t.username = ?", username);
            if (u == null) throw new IllegalStateException("No user " + username);
            return Auth.createAccessToken(u);
        }
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
