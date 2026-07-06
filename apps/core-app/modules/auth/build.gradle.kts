plugins {
    `java-library`
}

// Deliberately no dependencies on other modules — cross-module access must
// go through this module's ..api.. package (enforced by ModuleBoundaryArchTest
// in :bootstrap) or through the event bus, never a direct project() dependency.
