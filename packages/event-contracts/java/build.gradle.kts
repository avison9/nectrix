plugins {
    `java-library`
    id("com.google.protobuf") version "0.10.0"
}

group = "com.nectrix"
version = "0.1.0"

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

dependencies {
    // Pinned to match Spring Boot 4.1's managed protobuf-java version — core-app's
    // dependency-management BOM overrides any higher version declared here, and a
    // mismatched gencode/runtime version throws at class-init time.
    api("com.google.protobuf:protobuf-java:4.34.2")
    api("com.google.protobuf:protobuf-java-util:4.34.2")

    // TICKET-007 — vanilla Kafka client, NOT spring-kafka: this library must stay
    // framework-agnostic (no Spring dependency exists here today, and any future
    // non-Spring consumer must be able to use it). Pinned to the exact broker
    // version docker-compose.yml/CI already run (apache/kafka:3.8.0) — Kafka
    // clients are broadly cross-version compatible, but matching the broker
    // avoids any protocol-negotiation surprises.
    api("org.apache.kafka:kafka-clients:3.8.0")
    // TICKET-007 — self-contained Redis-backed idempotency-dedup default
    // (RedisDeduplicator), same "built ahead of TICKET-008, flagged for
    // consolidation" precedent as auth's RateLimiterService. Plain Jedis, not
    // Spring's StringRedisTemplate — same framework-agnostic reasoning as above.
    implementation("redis.clients:jedis:5.2.0")

    testImplementation(platform("org.junit:junit-bom:6.1.1"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.assertj:assertj-core:3.27.6")
}

sourceSets {
    main {
        proto {
            srcDir("../proto")
        }
    }
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:4.34.2"
    }
}

tasks.test {
    // Integration-tagged tests need a real Kafka broker (docker-compose.yml) —
    // excluded here so a plain `./gradlew build` never needs one running.
    // Same split as apps/core-app/bootstrap/build.gradle.kts.
    useJUnitPlatform {
        excludeTags("integration")
    }
}

tasks.register<Test>("integrationTest") {
    description = "Runs @Tag(\"integration\") tests against a real Kafka broker (see docker-compose.yml)."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("integration")
    }
}
