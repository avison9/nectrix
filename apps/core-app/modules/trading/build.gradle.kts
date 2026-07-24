plugins {
    `java-library`
    // Aligns this module's Spring dependency versions with bootstrap's Spring
    // Boot 4.1.0 BOM — same pattern as modules/audit.
    id("io.spring.dependency-management") version "1.1.7"
}

// TICKET-114 — modules:invitations/modules:social (below) pull software.amazon.awssdk:kms in
// transitively via modules:crypto, needing this module's own BOM import to resolve it on its own
// classpath (Gradle's BOM-managed versions don't propagate transitively across subprojects — same
// gotcha every other module's build.gradle.kts that touches invitations/social/crypto documents).
dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:4.1.0")
        mavenBom("software.amazon.awssdk:bom:2.31.68")
    }
}

// TICKET-114 — this module's first dependency on other bounded-context modules:
// IndividualCopySetupService validates broker-account ownership via invitations' published
// BrokerAccountLookupApi, and obtains/creates the private master_profiles row via social's
// published IndividualProfileApi -- never either module's ..service../..repository.. directly
// (enforced by ModuleBoundaryArchTest). Same one-way, no-cycle shape modules:social already has
// with modules:invitations -- neither invitations nor social depends back on trading.
dependencies {
    implementation(project(":modules:invitations"))
    implementation(project(":modules:social"))
    // TICKET-118 follow-up — NotificationDispatchApi, for ProspectNominationService to notify a
    // Master when a Follower nominates a prospect. Safe one-way edge: notifications has zero
    // dependencies of its own on any bounded-context module, so this can never cycle.
    implementation(project(":modules:notifications"))
    // Bugfix — CopyRelationshipController.toView resolves a real masterDisplayName/
    // followerDisplayName (UserAdminApi#getUser) instead of leaving the client to show a raw
    // relationship/broker-account UUID. auth has zero dependencies of its own on any
    // bounded-context module, so this can never cycle either.
    implementation(project(":modules:auth"))
    implementation("org.springframework.boot:spring-boot-starter-jdbc") // JdbcTemplate (money_management_profiles)
    // TICKET-111 — CopyRelationshipController.
    implementation("org.springframework.boot:spring-boot-starter-web") // @RestController
    // @PreAuthorize/@PostAuthorize, Authentication — just the annotations/types,
    // not the full starter (auth module owns the actual SecurityFilterChain).
    implementation("org.springframework.security:spring-security-core")
    // org.springframework.security.oauth2.jwt.Jwt principal type for
    // @AuthenticationPrincipal Jwt bindings.
    implementation("org.springframework.security:spring-security-oauth2-jose")
    // TICKET-120 — AgreementDocumentStorageClient's real AWS S3 SDK v2 client (MinIO locally/in
    // CI, real AWS S3 in production), same "each module/app separately imports the AWS SDK BOM"
    // reasoning as bootstrap's own ArchivalBlobStorageClient dependency.
    implementation("software.amazon.awssdk:s3")
}
