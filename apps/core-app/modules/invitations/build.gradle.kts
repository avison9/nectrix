plugins {
    `java-library`
    // Aligns this module's Spring dependency versions with bootstrap's Spring
    // Boot 4.1.0 BOM — same pattern as modules/auth (this module isn't a
    // Spring Boot application itself, so it needs its own BOM alignment).
    id("io.spring.dependency-management") version "1.1.7"
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:4.1.0")
    }
}

// Deliberately no dependencies on other modules — cross-module access must
// go through this module's ..api.. package (enforced by ModuleBoundaryArchTest
// in :bootstrap) or through the event bus, never a direct project() dependency.
dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web") // @RestController
    implementation("org.springframework.boot:spring-boot-starter-jdbc") // JdbcTemplate
    // @PreAuthorize/@PostAuthorize, Authentication, AccessDeniedException —
    // just the annotations/types, not the full starter (auth module owns
    // the actual SecurityFilterChain/JwtDecoder configuration).
    implementation("org.springframework.security:spring-security-core")
}
