plugins {
    `java-library`
    // Aligns this module's Spring dependency versions with bootstrap's Spring
    // Boot 4.1.0 BOM — same pattern as modules/crypto.
    id("io.spring.dependency-management") version "1.1.7"
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:4.1.0")
    }
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-jdbc") // JdbcTemplate (audit_log)
}
