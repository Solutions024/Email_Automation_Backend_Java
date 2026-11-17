package com.nagad.npsb.api;

import com.nagad.npsb.config.NpsbProperties;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** CSRF protection: a state-changing request a browser sends from another site is refused; the frontend's pass. */
class CrossSiteRequestsTest {
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private static HttpResponse<String> post(String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(ApiTestServer.url() + "/api/auth/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"login\":\"nobody\",\"password\":\"x\"}"));
        for (int i = 0; i < headers.length; i += 2) b.header(headers[i], headers[i + 1]);
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void requestsFromAnotherSiteAreRefused() throws Exception {
        HttpResponse<String> r = post("Origin", "https://evil.example", "Sec-Fetch-Site", "cross-site");
        assertEquals(403, r.statusCode());
        assertEquals("{\"detail\":\"Cross-site request refused\"}", r.body());
        assertEquals(403, post("Origin", "https://evil.example").statusCode(), "older browsers: Origin only");
        assertEquals(403, post("Origin", "null").statusCode());
    }

    @Test
    void theFrontendAndOtherClientsPass() throws Exception {
        String frontend = ApiTestServer.bean(NpsbProperties.class).frontendOrigin();
        for (HttpResponse<String> r : List.of(
                post(),                                                                    // curl, scripts
                post("Sec-Fetch-Site", "same-origin", "Origin", "http://localhost:5173"),  // through the /api proxy
                post("Sec-Fetch-Site", "same-site", "Origin", "http://localhost:9999"),
                post("Sec-Fetch-Site", "cross-site", "Origin", frontend))) {              // the frontend CORS allows
            assertNotEquals(403, r.statusCode(), r.body());
        }
    }
}
