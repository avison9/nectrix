plugins {
    `java-library`
    // Aligns this module's Spring dependency versions with bootstrap's Spring
    // Boot 4.1.0 BOM — same pattern as modules/auth (this module isn't a
    // Spring Boot application itself, so it needs its own BOM alignment).
    id("io.spring.dependency-management") version "1.1.7"
}

// TICKET-101 — this module pulls software.amazon.awssdk:kms in transitively via
// modules:crypto, but needs its own BOM import to resolve it on its own classpath
// (Gradle's BOM-managed versions don't propagate transitively across subprojects —
// same gotcha modules/auth's build.gradle.kts already documents, one level down
// the dependency graph).
dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:4.1.0")
        mavenBom("software.amazon.awssdk:bom:2.31.68")
    }
}

// TICKET-114 — modules:billing is now a second bounded-context exception (alongside modules:social's
// own identical one on this same module): BrokerLinkingService/MtLinkingService enforce Individual-
// mode master-slot/follower-slot capacity via billing's published CapabilityLimitsApi, never
// billing.service/billing.repository directly (enforced by ModuleBoundaryArchTest). Otherwise still
// no dependencies on other *bounded-context* modules — cross-module access must go through that
// module's ..api.. package or through the event bus, never a direct project() dependency.
// modules:crypto is the one shared-kernel exception (no domain data/business capability of its own)
// — same relationship modules/auth already has with it.
dependencies {
    // TICKET-114 — CapabilityLimitsApi, for master/follower-slot enforcement at link time.
    implementation(project(":modules:billing"))
    // TICKET-101 — EnvelopeEncryptionService, for BrokerLinkingService.
    implementation(project(":modules:crypto"))
    // Nectrix-hosted MT5/MT4 terminal-provisioning — AuditLogRepository, for
    // MtTerminalCredentialService's audit trail on every real plaintext-password
    // fetch. Shared-kernel utility (same tier as modules:crypto), not a
    // bounded-context module — consistent with the crypto exception above.
    implementation(project(":modules:audit"))
    implementation("org.springframework.boot:spring-boot-starter-web") // @RestController, RestClient
    implementation("org.springframework.boot:spring-boot-starter-jdbc") // JdbcTemplate
    // @PreAuthorize/@PostAuthorize, Authentication, AccessDeniedException —
    // just the annotations/types, not the full starter (auth module owns
    // the actual SecurityFilterChain/JwtDecoder configuration).
    implementation("org.springframework.security:spring-security-core")
    // TICKET-101 — just the org.springframework.security.oauth2.jwt.Jwt principal
    // type for @AuthenticationPrincipal Jwt bindings; this module does not
    // configure its own JwtDecoder (auth module's SecurityConfig owns that).
    implementation("org.springframework.security:spring-security-oauth2-jose")
    // TICKET-101 — the shared Redis client (packages/redis-client/java), for
    // OAuthLinkStateStore's CSRF-state/link-session storage. This module
    // deliberately does NOT declare its own UnifiedJedis @Bean — the one
    // instance modules/auth's RedisClientConfiguration defines is shared
    // across the whole application context (see OAuthLinkStateStore's Javadoc).
    implementation("com.nectrix:redis-client")
    // Jackson 3 (see modules/auth/build.gradle.kts's comment for the full
    // groupId-migration explanation) — for ObjectMapper (OAuthLinkStateStore,
    // BrokerLinkingService's credential-JSON serialization).
    implementation("tools.jackson.core:jackson-databind")
    // TICKET-101 — this platform's first real Java business Kafka producer:
    // BrokerConnectionEvent, published by the internal connection-status
    // endpoint and the token-refresh job. packages/event-contracts/java is a
    // shared-kernel-style library (like modules:crypto/redis-client), not a
    // bounded-context module, so a direct dependency here is consistent with
    // those.
    implementation("com.nectrix:event-contracts")
}
