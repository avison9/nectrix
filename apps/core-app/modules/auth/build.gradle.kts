plugins {
    `java-library`
    // Aligns this module's Spring dependency versions with bootstrap's Spring
    // Boot 4.1.0 BOM — needed because this module directly compiles against
    // Spring Security/JDBC/OAuth2-resource-server/Redis types (SecurityConfig,
    // repositories, services all live here, not in bootstrap).
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
    implementation("org.springframework.boot:spring-boot-starter-web") // RestClient, @RestController
    // spring-boot-starter-web pulls jackson-databind in transitively, but only
    // onto the RUNTIME classpath of a consumer that declares it as
    // `implementation` (same visibility rule as bootstrap<->auth) — this
    // module's own controllers (AuthController) compile directly against
    // ObjectMapper, so it needs to be declared here too. Spring Boot 4 /
    // Spring Framework 7 default to Jackson 3, whose group id moved to
    // `tools.jackson.core` (NOT the legacy `com.fasterxml.jackson.core`
    // coordinate, which the Boot BOM still separately manages at 2.x for
    // apps that haven't migrated — using that one here gives you a second,
    // unrelated ObjectMapper class that Spring's autoconfigured bean can
    // never satisfy).
    implementation("tools.jackson.core:jackson-databind")
    implementation("org.springframework.boot:spring-boot-starter-jdbc") // JdbcTemplate
    implementation("org.springframework.boot:spring-boot-starter-security")
    // Validates our own HS256-signed access tokens on incoming requests.
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    // Rate limiting (Redis INCR+EXPIRE) — see auth.service.RateLimiterService.
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    // No version pinned — already a transitive dependency of
    // oauth2-resource-server, so the BOM above picks a version guaranteed
    // compatible with the rest of the Security/JOSE stack. Used directly (not
    // just transitively) to issue our own JWTs and to verify Google/Apple's
    // RS256 ID tokens via JWKS.
    implementation("com.nimbusds:nimbus-jose-jwt")
    // Argon2PasswordEncoder delegates to BouncyCastle's Argon2BytesGenerator,
    // which spring-security-crypto does NOT bundle (optional/provided in its
    // own POM) — without this, ClassNotFoundException at first encode()/
    // matches() call, not at compile time.
    // 1.79 has a real, fixed CRITICAL-severity CVE (CVE-2025-14813, GOSTCTR
    // implementation mishandles >255 blocks) — caught by CI's post-merge
    // Trivy image scan (Main Pipeline's build-scan-push job), fixed by
    // bumping to 1.84 (also fixed in 1.80.2/1.81.1, but 1.84 is newest).
    implementation("org.bouncycastle:bcprov-jdk18on:1.84")
    // TOTP + QR generation — not managed by Boot's BOM, needs an explicit version.
    implementation("dev.samstevens.totp:totp:1.7.1")
}
