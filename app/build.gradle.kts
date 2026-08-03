plugins {
    // Adds `run` and the start scripts / distribution for a CLI application.
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

    // Core starter only -- no `spring-boot-starter-web`, so no embedded server.
    implementation(libs.spring.boot.starter)

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
