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

    testImplementation(libs.spring.boot.starter.test)
    testRuntimeOnly(libs.junit.platform.launcher)
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
}

// bootRun otherwise runs from app/, where it would miss the .env at the repo root.
tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    workingDir = rootProject.projectDir
}
