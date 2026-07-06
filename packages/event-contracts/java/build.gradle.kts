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

    testImplementation(platform("org.junit:junit-bom:6.1.1"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
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
    useJUnitPlatform()
}
