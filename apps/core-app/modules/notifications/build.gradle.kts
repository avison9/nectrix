plugins {
    `java-library`
    // Aligns this module's Spring dependency versions with bootstrap's Spring
    // Boot 4.1.0 BOM — same pattern as modules/billing/modules/social.
    id("io.spring.dependency-management") version "1.1.7"
}

// TICKET-115 — this module pulls software.amazon.awssdk:kms in transitively via modules:crypto's
// own dependents elsewhere in the build, but (like every other module that touches an AWS SDK
// artifact directly) needs its own BOM import to resolve software.amazon.awssdk:ses on its own
// classpath — same gotcha every other AWS-SDK-touching module's build.gradle.kts documents.
dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:4.1.0")
        mavenBom("software.amazon.awssdk:bom:2.31.68")
    }
}

// Deliberately no dependencies on other bounded-context modules — cross-module access goes
// through NotificationTargetLookupRepository's own raw-SQL reads (same "read another module's
// table directly via SQL, not its Java repository class" precedent modules:billing's own
// SettlementDataRepository already established) or the event bus, never a direct project()
// dependency (enforced by ModuleBoundaryArchTest).
dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web") // @RestController
    implementation("org.springframework.boot:spring-boot-starter-jdbc") // JdbcTemplate
    implementation("org.springframework.boot:spring-boot-starter-validation") // @ConfigurationProperties
    // @PreAuthorize et al. — auth module owns the actual SecurityFilterChain, same convention
    // every other module follows.
    implementation("org.springframework.security:spring-security-core")
    // org.springframework.security.oauth2.jwt.Jwt principal type for @AuthenticationPrincipal
    // Jwt bindings.
    implementation("org.springframework.security:spring-security-oauth2-jose")
    // TICKET-115 — the 4 Kafka consumers' IdempotentConsumer<T>/RetryPolicy helper + the event
    // proto types (CopiedTradeEvent/BrokerConnectionEvent/RiskEvent/BillingEvent) they parse.
    implementation("com.nectrix:event-contracts")
    // TICKET-115 — RedisDeduplicator, same fast-path dedup discipline TICKET-110's
    // BrokerConnectionEventConsumer already established for this exact kind of consumer.
    implementation("com.nectrix:redis-client")
    // Jackson 3 (see modules/auth/build.gradle.kts's comment for the full groupId-migration
    // explanation) — for building notification_log's JSON payload.
    implementation("tools.jackson.core:jackson-databind")
    // TICKET-115 — push delivery. Firebase Admin SDK covers both Android (FCM) and iOS (relays
    // through APNs internally for tokens registered via Firebase) in one integration — no
    // separate raw APNs HTTP/2+JWT client (confirmed with product: standard, widely-used
    // approach, avoids Apple's own cert/JWT signing flow for no functional gain at MVP stage).
    implementation("com.google.firebase:firebase-admin:9.4.1")
    // TICKET-115 — email delivery.
    implementation("software.amazon.awssdk:ses")
}
