plugins {
    java
    application
    id("org.springframework.boot") version "4.1.0"
    id("io.spring.dependency-management") version "1.1.7"
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
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
