package com.nagad.npsb.config;

import com.nagad.npsb.NpsbApplication;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.system.ApplicationHome;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Makes the values of the .env file (see .env.example) available to Spring, below the real environment variables
 * (like load_dotenv(): the environment wins), so application.yml's ${SMTP_HOST:...} placeholders read them.
 * Also works out the backend folder and the application log folder (npsb.backend-dir, npsb.app-log-dir),
 * which logback-spring.xml needs before the rest of the configuration is read.
 * Registered in META-INF/spring.factories.
 */
public class DotenvEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {
    static final String DOTENV_SOURCE = "dotenv";
    static final String DERIVED_SOURCE = "npsbDerived";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (environment.getPropertySources().contains(DERIVED_SOURCE)) return;  // once per environment
        Path backendDir = backendDir();
        Map<String, Object> values = new LinkedHashMap<>(DotenvParser.read(DotenvParser.find(backendDir)));
        if (environment.getPropertySources().contains(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)) {
            environment.getPropertySources().addAfter(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                    new MapPropertySource(DOTENV_SOURCE, values));
        } else {
            environment.getPropertySources().addLast(new MapPropertySource(DOTENV_SOURCE, values));
        }

        // A relative APP_LOG_DIR is taken from the backend folder (default: backend/app/logs)
        String logDir = environment.getProperty("APP_LOG_DIR", "app/logs");
        Map<String, Object> derived = new LinkedHashMap<>();
        derived.put("npsb.backend-dir", backendDir.toString());
        derived.put("npsb.app-log-dir", backendDir.resolve(logDir).normalize().toAbsolutePath().toString());
        environment.getPropertySources().addFirst(new MapPropertySource(DERIVED_SOURCE, derived));
    }

    /**
     * The backend folder (where build.gradle.kts and .env are), wherever the server is started from: from the
     * compiled classes (build/classes/java/main) or the jar (build/libs) upwards, else the jar's folder.
     */
    static Path backendDir() {
        try {
            Path home = new ApplicationHome(NpsbApplication.class).getDir().toPath().toAbsolutePath();
            for (Path dir = home; dir != null; dir = dir.getParent()) {
                if (Files.isRegularFile(dir.resolve("build.gradle.kts"))) return dir;
            }
            return home;
        } catch (RuntimeException e) {
            return Paths.get("").toAbsolutePath();
        }
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 5;  // before the config files (application.yml) are read
    }
}
