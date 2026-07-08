plugins {
    `java-library`
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
    // Plain Jedis, not Spring Data Redis — this library must stay usable by any
    // future Java service, not just Spring ones (same reasoning TICKET-007's
    // RedisDeduplicator already established, api() so JedisCluster/JedisPool
    // types are visible to consumers building their own RedisClientConfig).
    api("redis.clients:jedis:5.2.0")

    testImplementation(platform("org.junit:junit-bom:6.1.1"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.assertj:assertj-core:3.27.6")
}

tasks.test {
    // Integration-tagged tests need a real Redis instance (docker-compose.yml) —
    // excluded here so a plain `./gradlew build` never needs one running.
    // Same split as apps/core-app/bootstrap/build.gradle.kts and event-contracts/java.
    useJUnitPlatform {
        excludeTags("integration")
    }
}

tasks.register<Test>("integrationTest") {
    description = "Runs @Tag(\"integration\") tests against a real Redis instance (see docker-compose.yml)."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("integration")
    }
}
