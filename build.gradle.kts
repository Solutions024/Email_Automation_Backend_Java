plugins {
    java
    application
    alias(libs.plugins.shadow)
}

group = "com.nagad"
version = "1.0.0"
description = "NPSB Dispute Email Automation - backend (Java)"

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.tomcat.embed.core)
    implementation(libs.jackson.databind)
    implementation(libs.hikaricp)
    implementation(libs.mysql.connector)
    implementation(libs.jakarta.mail)
    implementation(libs.poi.ooxml)
    implementation(libs.jsoup)
    runtimeOnly(libs.h2)
    runtimeOnly(libs.slf4j.nop)  // silences HikariCP's logging

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// Java 17 bytecode and API, whichever newer JDK runs the build
tasks.withType<JavaCompile>().configureEach {
    options.release = 17
    options.encoding = "UTF-8"
}

application {
    mainClass = "com.nagad.npsb.Application"
}

// ./gradlew test: the API tests start the backend on an in-memory H2 database, never MySQL,
// and never read .env (NPSB_ENV_FILE=none)
tasks.test {
    useJUnitPlatform()
    environment(
        mapOf(
            "NPSB_ENV_FILE" to "none",
            "DATABASE_URL" to "jdbc:h2:mem:npsbtest;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
            "MAIL_PROVIDER" to "smtp",
            "SMTP_HOST" to "127.0.0.1",
            "SMTP_PORT" to "9",
            "SMTP_MAX_ATTEMPTS" to "1",
            "SMTP_RETRY_SECONDS" to "0",
            "INBOX_ENABLED" to "false",
            "EMAIL_DNS_CHECK" to "false",
            "JWT_SECRET" to "test-only-secret-not-used-anywhere-else",
            "APP_LOG_DIR" to layout.buildDirectory.dir("test-logs").get().asFile.absolutePath,
        )
    )
}

// The thin jar is only an intermediate; the one to run is the shadow jar below
tasks.jar {
    archiveClassifier = "plain"
}

// one runnable jar with all dependencies: build/libs/npsb-backend.jar
tasks.shadowJar {
    archiveFileName = "npsb-backend.jar"
    // Like maven-shade: service files from every jar are merged (keeps both JDBC drivers, the mail providers, ...);
    // any other duplicate file keeps the first copy.
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    mergeServiceFiles()
    filesNotMatching("META-INF/services/**") {
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
}
