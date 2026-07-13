plugins {
    java
    application
    id("org.springframework.boot") version "4.1.0"
    id("io.spring.dependency-management") version "1.1.7"
}

// TICKET-011 — Gradle's BOM-managed versions don't propagate transitively
// across subprojects (each consumer resolves its own classpath
// independently): modules:crypto imports this same BOM for its own
// software.amazon.awssdk:kms dependency, but bootstrap (which pulls that in
// transitively via modules:auth -> modules:crypto) needs its own import too,
// or resolution fails with "Could not find software.amazon.awssdk:kms:."
// (confirmed by hitting that exact error) — same reason every module here
// separately imports the Spring Boot BOM rather than relying on inheriting
// it from bootstrap.
dependencyManagement {
    imports {
        mavenBom("software.amazon.awssdk:bom:2.31.68")
    }
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    // TICKET-010 — /actuator/prometheus scrape endpoint (Micrometer's
    // http.server.requests timer -> Prometheus histogram), zero custom
    // metric code needed for request latency/error-rate dashboard panels.
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.micrometer:micrometer-registry-prometheus")
    // Structured JSON logging with field masking (allow-listed sensitive
    // fields, see logback-spring.xml) — not managed by Spring Boot's BOM,
    // version pinned explicitly.
    implementation("net.logstash.logback:logstash-logback-encoder:8.0")
    // logstash-logback-encoder 8.0 transitively pulls the legacy
    // com.fasterxml.jackson.core:jackson-databind (Spring Boot 4.1's own
    // Jackson is the separately-coordinated tools.jackson.core:jackson-databind
    // 3.x, so this doesn't just inherit a safe version from there) — CI's
    // Trivy gate caught two real HIGH CVEs (CVE-2026-54512/54513) on an
    // unpinned transitive resolution; forced to a version both are fixed in.
    implementation("com.fasterxml.jackson.core:jackson-databind:2.21.4")
    // TICKET-006 — GlobalSecurityExceptionHandler compiles directly against
    // AccessDeniedException; just the core annotations/types, not the full
    // security starter (auth module owns the actual SecurityFilterChain).
    implementation("org.springframework.security:spring-security-core")
    // Driver only — no liquibase-core here at all (see db/build.gradle.kts's
    // top comment). The app connects as the restricted `nectrix_app` role;
    // migrations are always run separately, by the db subproject, as the
    // superuser.
    // 42.7.7 has a real, fixed HIGH-severity CVE (CVE-2026-42198, client-side
    // DoS) — caught by CI's Trivy gate, fixed by bumping to 42.7.11+.
    runtimeOnly("org.postgresql:postgresql:42.7.11")
    implementation("com.nectrix:event-contracts")

    implementation(project(":modules:auth"))
    implementation(project(":modules:invitations"))
    implementation(project(":modules:social"))
    implementation(project(":modules:billing"))
    implementation(project(":modules:admin"))
    implementation(project(":modules:analytics"))
    implementation(project(":modules:notifications"))
    implementation(project(":modules:trading"))

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("com.tngtech.archunit:archunit-junit5:1.4.2")
    testImplementation(project(":archunit-fixtures"))
    // Test-only — computes real TOTP codes against a known secret the same way
    // TwoFactorService's CodeVerifier does, for TICKET-005's AuthIntegrationTest.
    testImplementation("dev.samstevens.totp:totp:1.7.1")
    // TICKET-101 — modules:crypto is only `implementation` (not `api`) inside
    // modules:invitations, so it doesn't propagate to bootstrap's compile
    // classpath transitively (Gradle java-library visibility rules); needed
    // directly here for BrokerAccountOAuthIntegrationTest's real
    // EnvelopeEncryptionService-backed test fixture setup.
    testImplementation(project(":modules:crypto"))
    // Same visibility reason as modules:crypto above — needed directly here for
    // BrokerAccountMtTerminalCredentialsIntegrationTest's real AuditLogRepository-backed
    // assertions (confirming a real audit_log row was written).
    testImplementation(project(":modules:audit"))
}

application {
    mainClass.set("com.nectrix.coreapp.bootstrap.CoreAppApplication")
}

tasks.test {
    // Integration-tagged tests need ephemeral infra (docker-compose.yml) running —
    // excluded here so a plain `./gradlew build` never needs Postgres/Redis/Kafka up.
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
