plugins {
    `java-library`
    // Aligns this module's Spring dependency versions with bootstrap's Spring
    // Boot 4.1.0 BOM — same pattern as modules/audit.
    id("io.spring.dependency-management") version "1.1.7"
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:4.1.0")
    }
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-jdbc") // JdbcTemplate (money_management_profiles)
    // TICKET-111 — CopyRelationshipController.
    implementation("org.springframework.boot:spring-boot-starter-web") // @RestController
    // @PreAuthorize/@PostAuthorize, Authentication — just the annotations/types,
    // not the full starter (auth module owns the actual SecurityFilterChain).
    implementation("org.springframework.security:spring-security-core")
    // org.springframework.security.oauth2.jwt.Jwt principal type for
    // @AuthenticationPrincipal Jwt bindings.
    implementation("org.springframework.security:spring-security-oauth2-jose")
}
