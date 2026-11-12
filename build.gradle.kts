plugins {
    java
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

group = "com.nagad"
version = "1.0.0"
description = "NPSB Dispute Email Automation - backend (Spring Boot)"

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.security)
    implementation(libs.spring.boot.starter.mail)
    implementation(libs.angus.mail)  // compiled against: IMAP / SMTP classes (only at runtime in the starter)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.springdoc.openapi)
    implementation(libs.poi.ooxml)
    implementation(libs.jsoup)
    runtimeOnly(libs.mysql.connector)
    runtimeOnly(libs.h2)

    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)
    annotationProcessor(libs.spring.boot.configuration.processor)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.jackson2.databind)
    testCompileOnly(libs.lombok)
    testAnnotationProcessor(libs.lombok)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// Java 17 bytecode and API, whichever newer JDK runs the build
tasks.withType<JavaCompile>().configureEach {
    options.release = 17
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-parameters"))
}

// The settings every test run gets: an in-memory H2 database (never MySQL), no .env (NPSB_ENV_FILE=none),
// and a mail server address where nothing listens (127.0.0.1:9), so sending fails fast instead of mailing anyone
// SERVER_CONTEXT_PATH empty: the API and contract tests call the routes at the root, as recorded
val testEnvironment = mapOf(
    "SERVER_CONTEXT_PATH" to "",
    "FRONTEND_DIR" to "",  // the API only (FrontendServingTest serves a frontend of its own)
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

// ./gradlew test: unit and API tests (the HTTP contract test has a task of its own, below)
tasks.test {
    useJUnitPlatform()
    environment(testEnvironment)
    exclude("com/nagad/npsb/contract/**")
}

// ./gradlew contractTest: the black-box HTTP contract (com.nagad.npsb.contract) against a fresh backend on its own
// in-memory database, compared with src/test/resources/contract/api-snapshot.json.
// ./gradlew contractTest -PcontractRecord=true records the snapshot again instead of comparing.
val contractRecord = providers.gradleProperty("contractRecord").map { it.toBoolean() }.orElse(false)
val contractTest = tasks.register<Test>("contractTest") {
    description = "Runs the HTTP contract test (record the snapshot with -PcontractRecord=true)."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    filter { includeTestsMatching("com.nagad.npsb.contract.*") }
    environment(testEnvironment + mapOf(
        "DATABASE_URL" to "jdbc:h2:mem:npsbcontract;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "CONTRACT_RECORD" to contractRecord.get().toString(),
    ))
    systemProperty("npsb.contract.record", contractRecord.get().toString())
    systemProperty("npsb.contract.snapshot",
        layout.projectDirectory.file("src/test/resources/contract/api-snapshot.json").asFile.absolutePath)
    systemProperty("npsb.contract.actual",
        layout.buildDirectory.file("contract/api-snapshot.actual.json").get().asFile.absolutePath)
    systemProperty("npsb.contract.report",
        layout.buildDirectory.file("contract/contract-report.txt").get().asFile.absolutePath)
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStandardStreams = false
    }
    if (contractRecord.get()) outputs.upToDateWhen { false }  // recording always runs
    shouldRunAfter(tasks.test)
}
tasks.check { dependsOn(contractTest) }

// one runnable jar with all dependencies: build/libs/npsb-backend.jar
tasks.bootJar {
    archiveFileName = "npsb-backend.jar"
}

// the plain (library) jar is not needed
tasks.jar {
    enabled = false
}
