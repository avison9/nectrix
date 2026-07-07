// Liquibase tooling lives in its own subproject, deliberately separate from
// `bootstrap` — see db/README.md. This keeps liquibase-core off bootstrap's
// classpath entirely, so Spring Boot's LiquibaseAutoConfiguration
// (@ConditionalOnClass(SpringLiquibase.class)) can never activate there even
// by accident: the running app only ever connects as the restricted
// `nectrix_app` role (bootstrap/src/main/resources/application.yml), never
// as the superuser these migrations require.
plugins {
    id("org.liquibase.gradle") version "3.1.0"
}

buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        // Required on the *buildscript* classpath (not just liquibaseRuntime) —
        // LiquibasePlugin introspects CommandFactory at apply time, before
        // liquibaseRuntime is on the execution classpath.
        classpath("org.liquibase:liquibase-core:4.31.1")
    }
}

repositories {
    mavenCentral()
}

dependencies {
    liquibaseRuntime("org.liquibase:liquibase-core:4.31.1")
    liquibaseRuntime("org.postgresql:postgresql:42.7.7")
    liquibaseRuntime("info.picocli:picocli:4.7.7") // required by Liquibase 4.4+
}

val appRolePassword: String =
    System.getenv("POSTGRES_APP_PASSWORD")
        ?: throw GradleException("POSTGRES_APP_PASSWORD env var is not set (see .env.example)")

liquibase {
    activities.register("main") {
        arguments = mapOf(
            "logLevel" to "info",
            // searchPath anchors changelogFile resolution to this subproject's
            // own directory — Liquibase's resource accessor treats
            // changelogFile as relative to searchPath (or the invoking
            // working directory if unset, which is ambiguous/wrong here since
            // `./gradlew :db:...` is invoked from apps/core-app, not db/).
            "searchPath" to project.projectDir.toString(),
            "changelogFile" to "src/main/resources/db/changelog/db.changelog-master.yaml",
            "url" to "jdbc:postgresql://${System.getenv("POSTGRES_HOST") ?: "localhost"}:${System.getenv("POSTGRES_PORT") ?: "5432"}/${System.getenv("POSTGRES_DB") ?: "nectrix"}",
            "username" to (System.getenv("POSTGRES_USER") ?: "nectrix"),
            "password" to System.getenv("POSTGRES_PASSWORD"),
        )
        // Real declared method on Activity, not methodMissing-based — safe to
        // call from Kotlin (the activities{} block's other config uses plain
        // property assignment for the same reason).
        changelogParameters(mapOf("appRolePassword" to appRolePassword))
    }

    runList = "main"
}
