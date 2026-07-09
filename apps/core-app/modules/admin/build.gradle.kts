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
    // Same Jackson-3-not-2 landmine as modules/auth — spring-boot-starter-web
    // pulls this in transitively but only onto the RUNTIME classpath unless
    // declared here too, and this module's own code (AdminController)
    // compiles directly against ObjectMapper to build audit_log metadata JSON.
    implementation("tools.jackson.core:jackson-databind")
    implementation("org.springframework.boot:spring-boot-starter-jdbc") // JdbcTemplate (audit_log)
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
}
