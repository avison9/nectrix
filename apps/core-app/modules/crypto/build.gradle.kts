plugins {
    `java-library`
    // Aligns this module's Spring dependency versions with bootstrap's Spring
    // Boot 4.1.0 BOM — same pattern as modules/admin.
    id("io.spring.dependency-management") version "1.1.7"
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:4.1.0")
        mavenBom("software.amazon.awssdk:bom:2.31.68")
    }
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-jdbc") // JdbcTemplate (kms_key_versions)
    // TICKET-011 — real AWS KMS SDK client, endpoint-overridable so the exact
    // same code path talks to LocalStack locally/in CI and real AWS KMS in
    // production (see AwsEnvelopeKmsClient). No GCP KMS client yet — no cloud
    // is chosen, and LocalStack only emulates AWS; see apps/core-app/README.md.
    implementation("software.amazon.awssdk:kms")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // Test-only — KeyVersionRepository needs a real DataSource, and this
    // module (unlike bootstrap) isn't itself a Spring Boot application with
    // an autoconfigured one. Same version pin as bootstrap/build.gradle.kts
    // (42.7.7 has a real, fixed HIGH-severity CVE, CVE-2026-42198).
    testImplementation("org.postgresql:postgresql:42.7.11")
}

tasks.test {
    // Integration-tagged tests need ephemeral infra (docker-compose.yml,
    // including LocalStack) running — excluded here, same convention as
    // bootstrap/build.gradle.kts.
    useJUnitPlatform {
        excludeTags("integration")
    }
}

tasks.register<Test>("integrationTest") {
    description = "Runs @Tag(\"integration\") tests against ephemeral infra (see docker-compose.yml)."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("integration")
    }
}
