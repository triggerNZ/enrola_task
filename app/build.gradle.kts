plugins {
    // Declares the main class and adds `run` / the start scripts and distribution.
    application
    alias(libs.plugins.spring.boot)
}

repositories {
    mavenCentral()
}

dependencies {
    // Spring Boot's BOM: keeps every Spring/JUnit/Logback version aligned, so
    // the dependencies below are declared without explicit versions.
    implementation(platform(libs.spring.boot.dependencies))
    testImplementation(platform(libs.spring.boot.dependencies))

    // Embedded Tomcat + Spring MVC + Jackson: the app serves the chat over HTTP.
    // The client is chat.sh, which talks to it with curl.
    implementation(libs.spring.boot.starter.web)

    // The admin UI: Thymeleaf renders it, Spring Security keeps it to admins.
    implementation(libs.spring.boot.starter.thymeleaf)
    implementation(libs.spring.boot.starter.security)

    implementation(libs.langchain4j.open.ai)
    implementation(libs.langchain4j)

    // DataSource + JdbcClient + HikariCP. Versions come from the Boot BOM.
    implementation(libs.spring.boot.starter.jdbc)
    runtimeOnly(libs.postgresql)

    // Schema migrations. Both are needed: the first carries the autoconfiguration,
    // the second teaches Flyway how to talk to Postgres.
    implementation(libs.spring.boot.flyway)
    runtimeOnly(libs.flyway.database.postgresql)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.starter.webmvc.test)
    testImplementation(libs.spring.security.test)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit.jupiter)
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

application {
    mainClass = "com.enrola.EnrolaApplication"
}

tasks.named<Test>("test") {
    useJUnitPlatform()

    // Always run. Gradle would otherwise report UP-TO-DATE and skip the task when nothing
    // changed, which looks identical to a successful run -- and the suite is only a few
    // seconds. Delete this line to get incremental behaviour back.
    outputs.upToDateWhen { false }

    testLogging {
        // Print a line per test. Without this Gradle only reports failures, which makes a
        // container test that self-skipped (no Docker) look identical to one that passed.
        events(
            org.gradle.api.tasks.testing.logging.TestLogEvent.PASSED,
            org.gradle.api.tasks.testing.logging.TestLogEvent.SKIPPED,
            org.gradle.api.tasks.testing.logging.TestLogEvent.FAILED,
        )
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showCauses = true
        showStackTraces = true
    }
}

// bootRun otherwise runs from app/, where it would miss the .env at the repo root.
tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    workingDir = rootProject.projectDir
}
