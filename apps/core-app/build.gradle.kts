plugins {
    id("com.diffplug.spotless") version "8.8.0" apply false
}

subprojects {
    apply(plugin = "com.diffplug.spotless")

    repositories {
        mavenCentral()
    }

    plugins.withId("java") {
        configure<JavaPluginExtension> {
            toolchain {
                languageVersion.set(JavaLanguageVersion.of(21))
            }
        }
    }

    // TICKET-006 — Spring MVC's @PathVariable/@RequestParam need either an
    // explicit name (@PathVariable("id")) or the compiled class file to
    // retain real parameter names (javac's -parameters flag); without
    // either, a bare @PathVariable UUID id fails at request time with
    // "Name for argument of type [UUID] not specified" — a real,
    // previously-undetected bug (TICKET-005's own AuthController.oauthCallback
    // has this same bare-@PathVariable shape; it was never caught only
    // because no automated test ever actually hit that route). The
    // `org.springframework.boot` Gradle plugin sets this automatically, but
    // only for the one subproject it's applied to (bootstrap) — plain
    // java-library modules (auth, invitations, admin, ...) need it set
    // explicitly here, once, for all of them.
    tasks.withType<JavaCompile>().configureEach {
        options.compilerArgs.add("-parameters")
    }

    configure<com.diffplug.gradle.spotless.SpotlessExtension> {
        java {
            target("src/*/java/**/*.java")
            googleJavaFormat()
        }
    }
}
