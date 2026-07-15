plugins {
    `java-library`
    // Aligns this module's Spring dependency versions with bootstrap's Spring
    // Boot 4.1.0 BOM — same pattern as modules/invitations.
    id("io.spring.dependency-management") version "1.1.7"
}

// This module pulls software.amazon.awssdk:kms in transitively via
// modules:invitations -> modules:crypto, but needs its own BOM import to
// resolve it on its own classpath (Gradle's BOM-managed versions don't
// propagate transitively across subprojects — same gotcha modules/invitations'
// own build.gradle.kts documents, one level up the dependency graph).
dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:4.1.0")
        mavenBom("software.amazon.awssdk:bom:2.31.68")
    }
}

// TICKET-111 — modules:invitations is the one exception to "no dependencies on
// other bounded-context modules" (see modules/admin's identical exception):
// MasterProfileService validates primary_broker_account_id ownership via
// invitations' published BrokerAccountLookupApi, never invitations.domain/
// invitations.repository directly (enforced by ModuleBoundaryArchTest).
dependencies {
    implementation(project(":modules:invitations"))
    implementation("org.springframework.boot:spring-boot-starter-web") // @RestController
    implementation("org.springframework.boot:spring-boot-starter-jdbc") // JdbcTemplate
    // @PreAuthorize/@PostAuthorize, Authentication — just the annotations/types,
    // not the full starter (auth module owns the actual SecurityFilterChain).
    implementation("org.springframework.security:spring-security-core")
    // org.springframework.security.oauth2.jwt.Jwt principal type for
    // @AuthenticationPrincipal Jwt bindings.
    implementation("org.springframework.security:spring-security-oauth2-jose")
}
