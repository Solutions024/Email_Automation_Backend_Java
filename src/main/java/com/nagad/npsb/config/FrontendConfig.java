package com.nagad.npsb.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * The whole app on the backend's port: besides the API (/api/..., /docs, /livez...), the built frontend
 * (FRONTEND_DIR, default ../frontend/dist) is served, so http://localhost:8000/ and http://localhost:8000/login
 * open the app and the app calls http://localhost:8000/api/... on the same port.
 *
 * Built files are served as they are (/assets/...); any other address that is not a file (/, /login, /some/page)
 * gets index.html, and the app shows the right screen itself. /api/... is never the app: an unknown API path keeps
 * its 404 (or 307 for a trailing slash) and a wrong method its 405, exactly as before, because the API's routes are
 * matched before this lowest-priority handler. Without a build (or FRONTEND_DIR empty) nothing is registered and
 * the backend is the API only.
 *
 * Registered first (HIGHEST_PRECEDENCE) so Spring Boot's own "/**" static-files handler (for classpath:/static,
 * which this project doesn't use) steps aside: it is only added when no handler for "/**" exists yet.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class FrontendConfig implements WebMvcConfigurer {
    private static final Logger LOG = LoggerFactory.getLogger("npsb");

    private final NpsbProperties properties;

    public FrontendConfig(NpsbProperties properties) {
        this.properties = properties;
    }

    /** The built frontend folder (it has an index.html), or null. Relative to the backend folder. */
    Path frontendDir() {
        String dir = properties.getFrontendDir();
        if (dir == null || dir.isBlank()) return null;
        Path base = properties.getBackendDir() == null ? Paths.get("") : Paths.get(properties.getBackendDir());
        Path path = base.resolve(dir.trim()).normalize().toAbsolutePath();
        return Files.isRegularFile(path.resolve("index.html")) ? path : null;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        Path dir = frontendDir();
        if (dir == null) {
            if (properties.getFrontendDir() != null && !properties.getFrontendDir().isBlank()) {
                LOG.info("Frontend not served: no built frontend (index.html) in FRONTEND_DIR ({}). "
                        + "Run npm run build in the frontend folder to open the app on this port.", properties.getFrontendDir());
            }
            return;
        }
        registry.addResourceHandler("/**")
                .addResourceLocations(new FileSystemResource(dir.toString() + "/"))
                // revalidated every time (Last-Modified / ETag answer 304 when unchanged), so a new build shows at once
                .setCacheControl(CacheControl.noCache())
                .resourceChain(false)
                .addResolver(new AppResolver());
        LOG.info("Frontend served from {}: the app at / (e.g. /login), the API at /api", dir);
    }

    /** "/" itself: the static-files handler never serves an empty path, so it is forwarded to the app's page. */
    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        if (frontendDir() != null) registry.addViewController("/").setViewName("forward:/index.html");
    }

    /** A file of the build if it exists; otherwise index.html for a page address; never anything for /api/... */
    static final class AppResolver extends PathResourceResolver {
        @Override
        protected Resource getResource(String resourcePath, Resource location) throws IOException {
            if (resourcePath.equals("api") || resourcePath.startsWith("api/")) return null;  // the API's 404 / 307
            Resource file = super.getResource(resourcePath, location);  // checks the path stays inside the build
            if (file != null) return file;
            String last = resourcePath.substring(resourcePath.lastIndexOf('/') + 1);
            if (last.contains(".")) return null;  // a missing file (script, image...): 404, not the app
            Resource index = location.createRelative("index.html");
            return index.isReadable() ? index : null;
        }
    }
}
