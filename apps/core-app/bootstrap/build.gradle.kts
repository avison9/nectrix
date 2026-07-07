plugins {
    java
    application
    id("org.springframework.boot") version "4.1.0"
    id("io.spring.dependency-management") version "1.1.7"
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    // Driver only — no liquibase-core here at all (see db/build.gradle.kts's
    // top comment). The app connects as the restricted `nectrix_app` role;
    // migrations are always run separately, by the db subproject, as the
    // superuser.
    // 42.7.7 has a real, fixed HIGH-severity CVE (CVE-2026-42198, client-side
    // DoS) — caught by CI's Trivy gate, fixed by bumping to 42.7.11+.
    runtimeOnly("org.postgresql:postgresql:42.7.11")
    implementation("com.nectrix:event-contracts")

    implementation(project(":modules:auth"))
    implementation(project(":modules:invitations"))
    implementation(project(":modules:social"))
    implementation(project(":modules:billing"))
    implementation(project(":modules:admin"))
    implementation(project(":modules:analytics"))
    implementation(project(":modules:notifications"))

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("com.tngtech.archunit:archunit-junit5:1.4.2")
    testImplementation(project(":archunit-fixtures"))
}

application {
    mainClass.set("com.nectrix.coreapp.bootstrap.CoreAppApplication")
}

tasks.test {
    // Integration-tagged tests need ephemeral infra (docker-compose.yml) running —
    // excluded here so a plain `./gradlew build` never needs Postgres/Redis/Kafka up.
    useJUnitPlatform {
        excludeTags("integration")
    }
}

tasks.register<Test>("integrationTest") {
    description = "Runs @Tag(\"integration\") tests against ephemeral infra (see docker-compose.yml)."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("integration")
    }
}
