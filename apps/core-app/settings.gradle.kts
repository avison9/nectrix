rootProject.name = "core-app"

// Pulls in packages/event-contracts/java as a composite build so bootstrap
// and modules can depend on `com.nectrix:event-contracts` with source
// substitution — one canonical .proto definition, not a hand-copied jar.
includeBuild("../../packages/event-contracts/java")

include(
    "bootstrap",
    "db",
    "archunit-fixtures",
    "modules:crypto",
    "modules:audit",
    "modules:auth",
    "modules:invitations",
    "modules:social",
    "modules:billing",
    "modules:admin",
    "modules:analytics",
    "modules:notifications",
)
