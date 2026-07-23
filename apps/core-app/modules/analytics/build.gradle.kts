plugins {
    `java-library`
    // Aligns this module's Spring dependency versions with bootstrap's Spring
    // Boot 4.1.0 BOM — same pattern as modules/invitations/modules/social.
    id("io.spring.dependency-management") version "1.1.7"
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:4.1.0")
    }
}

// Deliberately no dependencies on other *bounded-context* modules — cross-module access must
// go through this module's ..api.. package (enforced by ModuleBoundaryArchTest in :bootstrap)
// or through the event bus, never a direct project() dependency. TICKET-112's
// LeaderboardComputationRepository reads copy_relationships/copied_trades/account_snapshots/
// master_profiles directly via JdbcTemplate raw SQL (same "read another module's table
// directly via SQL, not its Java repository class" precedent modules:trading's own
// CopyRelationshipRepository already established by joining master_profiles) — that's a
// shared-database read, not a Java package dependency, so it doesn't need a project()
// dependency on modules:social/modules:trading and stays outside ArchUnit's rule.
dependencies {
    implementation("org.springframework.boot:spring-boot-starter-jdbc") // JdbcTemplate
    implementation("org.springframework:spring-context") // @Scheduled/@EnableScheduling annotations
    // TICKET-116 — MasterAnalyticsController (@RestController) + MasterAnalyticsService's
    // @PostAuthorize("@perms.isOwnerOrStaff(...)") ownership check, same convention
    // modules:trading/modules:social already use for their own ownership-gated endpoints.
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.security:spring-security-core")
    // Feature — org.springframework.security.oauth2.jwt.Jwt principal type for
    // FollowerAnalyticsController's @AuthenticationPrincipal Jwt binding, same "just the
    // annotations/types, not the full starter" precedent modules:trading's own build.gradle.kts
    // comment already establishes for this exact dependency.
    implementation("org.springframework.security:spring-security-oauth2-jose")
}
