plugins {
    `java-library`
    // Aligns this module's Spring dependency versions with bootstrap's Spring
    // Boot 4.1.0 BOM — needed because this module directly compiles against
    // Spring Security/JDBC/OAuth2-resource-server/Redis types (SecurityConfig,
    // repositories, services all live here, not in bootstrap).
    id("io.spring.dependency-management") version "1.1.7"
}

// TICKET-011 — Gradle's BOM-managed versions don't propagate transitively
// across subprojects: this module pulls software.amazon.awssdk:kms in
// transitively via modules:crypto, but needs its own BOM import to resolve
// it on its own classpath (see bootstrap/build.gradle.kts's comment for the
// full explanation — same gotcha, same fix, one level down the graph).
dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:4.1.0")
        mavenBom("software.amazon.awssdk:bom:2.31.68")
    }
}

// No dependencies on other *bounded-context* modules — cross-module access
// there must go through that module's ..api.. package (enforced by
// ModuleBoundaryArchTest in :bootstrap) or the event bus, never a direct
// project() dependency. modules:crypto is different: a shared-kernel utility
// (no domain data/business capability of its own, docs/04-architecture-overview.md
// §4.4's "Shared Kernel" layer), the same relationship admin has with
// auth/invitations' own ..api.. packages, just for a generic capability
// instead of a bounded context.
dependencies {
    // TICKET-011 — EnvelopeEncryptionService, for TwoFactorService.
    implementation(project(":modules:crypto"))
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
    // TICKET-008 — shared Redis client library (token-bucket rate limiting via
    // auth.service.RateLimiterService, which now delegates here instead of its
    // own TICKET-005-era Spring StringRedisTemplate INCR+EXPIRE counter).
    implementation("com.nectrix:redis-client")
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
    // TICKET-011 AC3 — StructuredArguments.kv tags the 2FA secret for
    // bootstrap's logback-spring.xml MaskingJsonGeneratorDecorator to redact
    // (same pattern as HelloController's TICKET-010 precedent). Compile-only
    // dependency here; the actual masking config/appender lives in bootstrap.
    // Same version pin as bootstrap/build.gradle.kts.
    implementation("net.logstash.logback:logstash-logback-encoder:8.0")
}
