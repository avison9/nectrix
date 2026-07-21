plugins {
    `java-library`
    // Aligns this module's Spring dependency versions with bootstrap's Spring
    // Boot 4.1.0 BOM — same pattern as modules/social/modules/analytics.
    id("io.spring.dependency-management") version "1.1.7"
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:4.1.0")
        // TICKET-120 — FeeReportDocumentStorageClient's AWS S3 SDK v2 dependency, same "each
        // module/app separately imports the BOM" reasoning as modules:trading's identical import.
        mavenBom("software.amazon.awssdk:bom:2.31.68")
    }
}

// TICKET-113 — reads copy_relationships/copied_trades/account_snapshots directly via JdbcTemplate
// raw SQL (same "read another module's table directly via SQL, not its Java repository class"
// precedent modules:analytics' own build.gradle.kts already established) — a shared-database
// read, not a Java package dependency, so it stays outside ArchUnit's module-boundary rule and
// needs no project() dependency on modules:trading.
dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web") // @RestController (internal Stripe webhook)
    implementation("org.springframework.boot:spring-boot-starter-jdbc") // JdbcTemplate
    implementation("org.springframework.boot:spring-boot-starter-validation") // @ConfigurationProperties
    implementation("org.springframework:spring-context") // @Scheduled/@EnableScheduling
    // @PreAuthorize et al. for the internal webhook route -- auth module owns the actual
    // SecurityFilterChain, same convention every other module follows.
    implementation("org.springframework.security:spring-security-core")
    // TICKET-114 — org.springframework.security.oauth2.jwt.Jwt principal type for
    // SubscriptionController's @AuthenticationPrincipal Jwt bindings; this module does not
    // configure its own JwtDecoder (auth module's SecurityConfig owns that).
    implementation("org.springframework.security:spring-security-oauth2-jose")
    implementation("com.nectrix:event-contracts") // BillingEvent (already scaffolded proto)
    implementation("com.stripe:stripe-java:29.4.0")
    // TICKET-120 — FeeReportDocumentStorageClient (MinIO locally/in CI, real AWS S3 in production).
    implementation("software.amazon.awssdk:s3")
    // TICKET-120 — BrokerFeeReportService's own audit trail on every send/confirm-deducted/
    // confirm-paid transition (AC5). Shared-kernel utility (same tier as modules:crypto), not a
    // bounded-context module — same reasoning modules:invitations' own audit dependency documents.
    implementation(project(":modules:audit"))
}
