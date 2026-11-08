package com.nagad.npsb.web;

import com.nagad.npsb.Config;
import com.nagad.npsb.PyJson;
import jakarta.servlet.MultipartConfigElement;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.catalina.Context;
import org.apache.catalina.Wrapper;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.startup.Tomcat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The HTTP server (embedded Tomcat) and the routing of requests to handlers, with FastAPI's behaviour:
 * {"detail": ...} errors, 422 validation errors, 404 / 405 for unknown paths / methods, CORS for the
 * frontend, and the API docs at /docs.
 */
public final class Server {
    private static final Logger LOG = Logger.getLogger("npsb");

    @FunctionalInterface
    public interface Handler {
        Object handle(Req req) throws Exception;
    }

    private record Route(String method, String pattern, String[] segments, int status, String summary, Handler handler) {}

    private final List<Route> routes = new ArrayList<>();
    private Tomcat tomcat;

    // ---------------------------------------------------------------- registering routes

    public void route(String method, String pattern, int status, String summary, Handler handler) {
        routes.add(new Route(method, pattern, pattern.substring(1).split("/", -1), status, summary, handler));
    }

    public void get(String pattern, String summary, Handler h) { route("GET", pattern, 200, summary, h); }
    public void post(String pattern, String summary, Handler h) { route("POST", pattern, 200, summary, h); }
    public void post(String pattern, int status, String summary, Handler h) { route("POST", pattern, status, summary, h); }
    public void put(String pattern, String summary, Handler h) { route("PUT", pattern, 200, summary, h); }
    public void patch(String pattern, String summary, Handler h) { route("PATCH", pattern, 200, summary, h); }
    public void delete(String pattern, String summary, Handler h) { route("DELETE", pattern, 200, summary, h); }

    private static Map<String, String> match(Route r, String[] parts) {
        if (r.segments.length != parts.length) return null;
        Map<String, String> params = new LinkedHashMap<>();
        for (int i = 0; i < parts.length; i++) {
            String seg = r.segments[i];
            if (seg.startsWith("{") && seg.endsWith("}")) {
                if (parts[i].isEmpty()) return null;
                params.put(seg.substring(1, seg.length() - 1), parts[i]);
            } else if (!seg.equals(parts[i])) {
                return null;
            }
        }
        return params;
    }

    // ---------------------------------------------------------------- serving

    public void start(String host, int port) throws Exception {
        tomcat = new Tomcat();
        tomcat.setBaseDir(Files.createTempDirectory("npsb-tomcat").toString());
        Connector connector = new Connector();
        connector.setPort(port);
        connector.setProperty("address", host);
        connector.setURIEncoding("UTF-8");
        connector.setMaxPostSize((int) Req.MAX_BODY_BYTES);  // form posts; JSON bodies are capped in Req.raw()
        connector.setThrowOnFailure(true);  // a port in use stops the start instead of only being logged
        tomcat.setConnector(connector);
        Context ctx = tomcat.addContext("", null);
        ctx.setAllowCasualMultipartParsing(true);
        Wrapper wrapper = Tomcat.addServlet(ctx, "api", new Dispatch());
        // uploads: at most MAX_FILE_BYTES per file and MAX_BODY_BYTES per request (they are read into memory)
        wrapper.setMultipartConfigElement(new MultipartConfigElement(null, Req.MAX_FILE_BYTES, Req.MAX_BODY_BYTES, 1024 * 1024));
        ctx.addServletMappingDecoded("/", "api");
        tomcat.start();
        LOG.info("Listening on http://" + host + ":" + port);
    }

    public void await() {
        tomcat.getServer().await();
    }

    public void stop() {
        try {
            if (tomcat != null) {
                tomcat.stop();
                tomcat.destroy();
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Stopping the web server failed", e);
        }
    }

    private final class Dispatch extends HttpServlet {
        @Override
        protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException {
            try {
                handle(request, response);
            } catch (Exception e) {
                LOG.log(Level.SEVERE, "Request failed: " + request.getMethod() + " " + request.getRequestURI(), e);
                if (!response.isCommitted()) {
                    response.reset();
                    response.setStatus(500);
                    response.setContentType("text/plain; charset=utf-8");
                    response.getOutputStream().write("Internal Server Error".getBytes(StandardCharsets.UTF_8));
                }
            }
        }
    }

    private void handle(HttpServletRequest request, HttpServletResponse response) throws Exception {
        String origin = request.getHeader("Origin");
        boolean allowedOrigin = origin != null && origin.equals(Config.APP_BASE_URL);
        String method = request.getMethod();

        // CORS preflight (CORSMiddleware)
        if (method.equals("OPTIONS") && origin != null && request.getHeader("Access-Control-Request-Method") != null) {
            response.setHeader("Vary", "Origin");
            if (!allowedOrigin) {
                response.setStatus(400);
                response.setContentType("text/plain; charset=utf-8");
                response.getOutputStream().write("Disallowed CORS origin".getBytes(StandardCharsets.UTF_8));
                return;
            }
            response.setHeader("Access-Control-Allow-Origin", origin);
            response.setHeader("Access-Control-Allow-Credentials", "true");
            response.setHeader("Access-Control-Allow-Methods", "DELETE, GET, HEAD, OPTIONS, PATCH, POST, PUT");
            String requested = request.getHeader("Access-Control-Request-Headers");
            if (requested != null) response.setHeader("Access-Control-Allow-Headers", requested);
            response.setHeader("Access-Control-Max-Age", "600");
            response.setStatus(200);
            response.setContentType("text/plain; charset=utf-8");
            response.getOutputStream().write("OK".getBytes(StandardCharsets.UTF_8));
            return;
        }
        if (allowedOrigin) {
            response.setHeader("Access-Control-Allow-Origin", origin);
            response.setHeader("Access-Control-Allow-Credentials", "true");
            response.setHeader("Vary", "Origin");
        }

        String path = request.getRequestURI().substring(request.getContextPath().length());
        path = java.net.URLDecoder.decode(path.replace("+", "%2B"), StandardCharsets.UTF_8);
        if (path.equals("/docs")) {
            writeBytes(response, 200, "text/html; charset=utf-8", docsPage().getBytes(StandardCharsets.UTF_8));
            return;
        }
        if (path.equals("/openapi.json")) {
            writeBytes(response, 200, "application/json", PyJson.MAPPER.writeValueAsBytes(openapi()));
            return;
        }

        String[] parts = path.length() > 1 ? path.substring(1).split("/", -1) : new String[]{""};
        Route found = null;
        Map<String, String> params = null;
        Set<String> allowed = new LinkedHashSet<>();
        for (Route r : routes) {
            Map<String, String> m = match(r, parts);
            if (m == null) continue;
            if (r.method.equals(method)) {
                found = r;
                params = m;
                break;
            }
            allowed.add(r.method);
        }
        if (found == null) {
            if (!allowed.isEmpty()) {
                response.setHeader("Allow", String.join(", ", allowed));
                writeJson(response, 405, Map.of("detail", "Method Not Allowed"));
                return;
            }
            // redirect_slashes: /api/users/ -> /api/users
            String other = path.endsWith("/") ? path.substring(0, path.length() - 1) : path + "/";
            if (!other.isEmpty() && !path.equals("/")) {
                String[] otherParts = other.substring(1).split("/", -1);
                for (Route r : routes) {
                    if (match(r, otherParts) != null) {
                        String query = request.getQueryString();
                        response.setStatus(307);
                        response.setHeader("Location", other + (query == null ? "" : "?" + query));
                        return;
                    }
                }
            }
            writeJson(response, 404, Map.of("detail", "Not Found"));
            return;
        }

        Req req = new Req(request, params);
        long started = System.nanoTime();
        int status = 500;
        String problem = null;
        try {
            Object result = found.handler.handle(req);
            status = req.res.status != 200 ? req.res.status : found.status;
            for (String[] h : req.res.headers) response.addHeader(h[0], h[1]);
            for (String c : req.res.cookies) response.addHeader("Set-Cookie", c);
            if (result instanceof Res.File f) {
                if (f.disposition != null) response.setHeader("Content-Disposition", f.disposition);
                writeBytes(response, status, f.contentType, f.data);
            } else {
                writeJson(response, status, result);
            }
        } catch (ApiError e) {
            status = e.status;
            problem = String.valueOf(e.detail);
            for (Map.Entry<String, String> h : e.headers.entrySet()) response.setHeader(h.getKey(), h.getValue());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("detail", e.detail);
            writeJson(response, e.status, body);
        } catch (ValidationError e) {
            status = 422;
            problem = String.valueOf(e.errors);
            writeJson(response, 422, Map.of("detail", e.errors));
        } finally {
            // application log: requests that change something, and every failed one (reads that work are left out)
            boolean probe = path.equals("/startupz") || path.equals("/livez") || path.equals("/readyz");
            if (!probe && (!method.equals("GET") || status >= 400)) {
                String text = com.nagad.npsb.AppLog.kv("method", method, "path", path, "status", status,
                        "ms", (System.nanoTime() - started) / 1_000_000, "ip", req.clientIp(), "problem", problem);
                if (status >= 500) LOG.warning("[HTTP] " + text);
                else com.nagad.npsb.AppLog.event("HTTP", text);
            }
            req.closeDb();
            Runnable after = req.afterResponse;
            if (after != null) {
                Thread t = new Thread(after, "background-task");
                t.setDaemon(true);
                t.start();
            }
        }
    }

    private static void writeJson(HttpServletResponse response, int status, Object body) throws IOException {
        writeBytes(response, status, "application/json", PyJson.MAPPER.writeValueAsBytes(body));
    }

    private static void writeBytes(HttpServletResponse response, int status, String contentType, byte[] data) throws IOException {
        response.setStatus(status);
        response.setContentType(contentType);
        response.setContentLength(data.length);
        response.getOutputStream().write(data);
    }

    // ---------------------------------------------------------------- API docs

    private Map<String, Object> openapi() {
        Map<String, Object> paths = new LinkedHashMap<>();
        for (Route r : routes) {
            @SuppressWarnings("unchecked")
            Map<String, Object> item = (Map<String, Object>) paths.computeIfAbsent(r.pattern, k -> new LinkedHashMap<>());
            List<Map<String, Object>> parameters = new ArrayList<>();
            for (String seg : r.segments) {
                if (seg.startsWith("{")) {
                    parameters.add(Map.of("name", seg.substring(1, seg.length() - 1), "in", "path", "required", true,
                            "schema", Map.of("type", seg.endsWith("_id}") ? "integer" : "string")));
                }
            }
            Map<String, Object> op = new LinkedHashMap<>();
            String tag = r.pattern.startsWith("/api/auth") ? "auth" : r.pattern.startsWith("/api/users") ? "users" : "default";
            op.put("tags", List.of(tag));
            op.put("summary", r.summary);
            op.put("parameters", parameters);
            if (!r.method.equals("GET") && !r.method.equals("DELETE")) {
                op.put("requestBody", Map.of("content", Map.of("application/json", Map.of("schema", Map.of("type", "object")))));
            }
            op.put("responses", Map.of(String.valueOf(r.status), Map.of("description", "Successful Response")));
            item.put(r.method.toLowerCase(), op);
        }
        return Map.of("openapi", "3.1.0", "info", Map.of("title", "NPSB Dispute Email Automation", "version", "0.1.0"),
                "paths", paths);
    }

    private static String docsPage() {
        return """
                <!DOCTYPE html>
                <html><head>
                <link type="text/css" rel="stylesheet" href="https://cdn.jsdelivr.net/npm/swagger-ui-dist@5/swagger-ui.css">
                <title>NPSB Dispute Email Automation - Swagger UI</title>
                </head><body>
                <div id="swagger-ui"></div>
                <script src="https://cdn.jsdelivr.net/npm/swagger-ui-dist@5/swagger-ui-bundle.js"></script>
                <script>
                const ui = SwaggerUIBundle({url: '/openapi.json', dom_id: '#swagger-ui', layout: 'BaseLayout', deepLinking: true,
                  presets: [SwaggerUIBundle.presets.apis, SwaggerUIBundle.SwaggerUIStandalonePreset]})
                </script>
                </body></html>
                """;
    }
}
