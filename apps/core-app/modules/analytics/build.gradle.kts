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
}
