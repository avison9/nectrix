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
    // TICKET-110 — the /ws/v1 broker-connection channel (docs/14-api-specification.md §14.11).
    // A narrow, single-channel plain WebSocketHandler (not full STOMP) — see
    // bootstrap/.../realtime package's own Javadoc for why.
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    // TICKET-110 — BrokerConnectionEventConsumer is this app's first real Kafka CONSUMER
    // (event-contracts was already a direct dependency here for other reasons; redis-client
    // wasn't yet, needed directly for RedisDeduplicator, same "implementation not api, must be
    // added directly" reasoning modules:crypto/modules:audit below already document).
    implementation("com.nectrix:redis-client")
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
    // TICKET-110 — BrokerConnectionWebSocketHandler verifies the WS connect-time
    // access_token against the SAME JwtDecoder bean SecurityConfig exposes for REST;
    // needs the resource-server module directly here for the Jwt/JwtDecoder/JwtException
    // types (modules:auth's own dependency on this is `implementation`, so it doesn't
    // propagate transitively, same "implementation not api" reasoning as redis-client above).
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    // Driver only — no liquibase-core here at all (see db/build.gradle.kts's
    // top comment). The app connects as the restricted `nectrix_app` role;
    // migrations are always run separately, by the db subproject, as the
    // superuser.
    // 42.7.7 has a real, fixed HIGH-severity CVE (CVE-2026-42198, client-side
    // DoS) — caught by CI's Trivy gate, fixed by bumping to 42.7.11+.
    runtimeOnly("org.postgresql:postgresql:42.7.11")
    implementation("com.nectrix:event-contracts")
    // TICKET-101 follow-up — ArchivalBlobStorageClient's real AWS S3 SDK v2 client (MinIO
    // locally/in CI via endpointOverride, real AWS S3 in production), same "each module/app
    // separately imports the AWS SDK BOM" reasoning as modules:crypto's own KMS dependency above.
    implementation("software.amazon.awssdk:s3")

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
    // Same visibility reason again — modules:billing's Stripe SDK dependency is `implementation`,
    // not `api`; SettlementIntegrationTest needs the SDK's own types directly to statically mock
    // Invoice/InvoiceItem (Mockito's inline mock maker, already active in this project).
    testImplementation("com.stripe:stripe-java:29.4.0")
}

application {
    mainClass.set("com.nectrix.coreapp.bootstrap.CoreAppApplication")
}

// The growing number of @SpringBootTest classes each get their own cached Spring context — many
// deliberately, via a per-class @DynamicPropertySource consumer-group id, to avoid Kafka
// partition-ownership races across tests (see BrokerConnectionEventConsumer's own comment).
// Spring Boot's own HikariCP default (maximum-pool-size: 10, minimum-idle defaulting to match it)
// means each of those contexts holds up to 10 idle connections open simultaneously; with dozens of
// distinct contexts alive at once in one Gradle test JVM, that exceeds Postgres's own default
// max_connections=100 well before any single test class is slow or broken — observed both locally
// and in CI's "Main Pipeline" as a cascade of CannotGetJdbcConnectionException/PSQLException across
// unrelated test classes, not a real regression in any of them. A system property (not a
// src/test/resources/application.yml) — Spring Boot only loads ONE classpath:/application.yml
// (whichever the classpath happens to put first), so a second one entirely REPLACES main's own
// rather than merging with it, silently dropping datasource.url/username/password and every other
// setting (caught the hard way: every context failed with "Failed to determine a suitable driver
// class" once tried). A system property layers on top of application.yml instead of replacing it.
val hikariTestPoolSizeProps =
    mapOf(
        "spring.datasource.hikari.maximum-pool-size" to "3",
        "spring.datasource.hikari.minimum-idle" to "1",
    )

tasks.test {
    // Integration-tagged tests need ephemeral infra (docker-compose.yml) running —
    // excluded here so a plain `./gradlew build` never needs Postgres/Redis/Kafka up.
    useJUnitPlatform {
        excludeTags("integration")
    }
    hikariTestPoolSizeProps.forEach { (k, v) -> systemProperty(k, v) }
}

tasks.register<Test>("integrationTest") {
    description = "Runs @Tag(\"integration\") tests against ephemeral infra (see docker-compose.yml)."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("integration")
    }
    hikariTestPoolSizeProps.forEach { (k, v) -> systemProperty(k, v) }
}
