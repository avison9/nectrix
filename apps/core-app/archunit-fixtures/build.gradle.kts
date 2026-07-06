plugins {
    `java-library`
}

// Deliberately outside com.nectrix.coreapp so it can never be swept into the
// production ModuleBoundaryArchTest's importPackages(BASE) scan — this module
// exists solely so ModuleBoundaryRuleSelfTest has a real, permanent violation
// to check the rule against.
