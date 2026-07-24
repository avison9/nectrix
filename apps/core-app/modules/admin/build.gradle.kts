plugins {
    `java-library`
    // Aligns this module's Spring dependency versions with bootstrap's Spring
    // Boot 4.1.0 BOM — same pattern as modules/auth.
    id("io.spring.dependency-management") version "1.1.7"
}

// TICKET-011 — Gradle's BOM-managed versions don't propagate transitively
// across subprojects: admin pulls software.amazon.awssdk:kms in transitively
// via auth -> crypto, but needs its own BOM import to resolve it on its own
// classpath (same reason bootstrap/build.gradle.kts needs this too — see its
// comment for the full explanation).
dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:4.1.0")
        mavenBom("software.amazon.awssdk:bom:2.31.68")
    }
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web") // @RestController
    // TICKET-117 — AdminRepository's own real Postgres queries (broker connection
    // counts, Copy Engine throughput/error-rate, reconciliation-drift rate) — this
    // module's AdminRepository was a placeholder interface until now, never needed
    // JdbcTemplate on its own classpath before.
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    // TICKET-117 — KafkaConsumerLagService's org.apache.kafka.clients.admin.AdminClient
    // (System Health's Kafka-lag card). Pulls in the same vanilla kafka-clients:3.8.0
    // this composite build already pins everywhere (see event-contracts/java's own
    // build.gradle.kts comment) — not declared for its own event types.
    implementation("com.nectrix:event-contracts")
    // Same Jackson-3-not-2 landmine as modules/auth — spring-boot-starter-web
    // pulls this in transitively but only onto the RUNTIME classpath unless
    // declared here too, and this module's own code (AdminController)
    // compiles directly against ObjectMapper to build audit_log metadata JSON.
    implementation("tools.jackson.core:jackson-databind")
    // @PreAuthorize, Authentication — just the annotations/types.
    implementation("org.springframework.security:spring-security-core")
    // The Jwt type (@AuthenticationPrincipal Jwt jwt) lives in oauth2-jose,
    // not spring-security-core.
    implementation("org.springframework.security:spring-security-oauth2-jose")
    // TICKET-006 — cross-module calls go through each module's ..api.. package
    // only (docs/04-architecture-overview.md §4.4), never a direct
    // repository/service import, even though ArchUnit's ModuleBoundaryRules
    // only technically restricts ..repository../..domain.. — this is the
    // intended discipline, not just the enforced minimum. Used for
    // ImpersonationApi (auth) and BrokerAccountLookupApi (invitations).
    implementation(project(":modules:auth"))
    implementation(project(":modules:invitations"))
    // TICKET-117 — FeeLedgerAdminApi (dispute raise/list/detail/resolve).
    implementation(project(":modules:billing"))
    // Engine Control page — RedisHealthCheck's real PING against the same shared,
    // cluster-aware client modules:auth's own RedisClientConfiguration already registers as a bean
    // (OAuthLinkStateStore/RateLimiterService) — one UnifiedJedis bean in bootstrap's single
    // ApplicationContext, autowired here by type, no cross-module import of any auth-module class
    // needed. Not yet a direct dependency of this module before now.
    implementation("com.nectrix:redis-client")
    // #421 — AdminCopyRelationshipApi (manual follower-master linking). One-way
    // edge (trading doesn't depend on admin), same shape as the billing edge above.
    implementation(project(":modules:trading"))
    // TICKET-125 — MasterProfileLookupApi#findByPrimaryBrokerAccountId, so the
    // master-broker-accounts-by-email lookup only ever offers accounts that already have a
    // master_profile (the real prerequisite linkFollowerToMaster's own AdminCopyLinkService
    // enforces) — a MASTER_ONLY/BOTH broker account alone isn't enough.
    implementation(project(":modules:social"))
    // AuditLogRepository — extracted into this shared-kernel module (same tier as
    // modules:crypto) once modules:invitations also needed to write audit_log
    // (the Nectrix-hosted MT5/MT4 terminal-provisioning work), exactly as that
    // class's own original Javadoc anticipated.
    implementation(project(":modules:audit"))
}
