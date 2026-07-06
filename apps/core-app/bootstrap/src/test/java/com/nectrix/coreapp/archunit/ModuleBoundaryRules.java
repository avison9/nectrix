package com.nectrix.coreapp.archunit;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.lang.ArchRule;

/**
 * Shared rule builder used by both the production check (ModuleBoundaryArchTest, run against the
 * real module tree) and the self-test (ModuleBoundaryRuleSelfTest, run against the deliberately
 * violating archunit-fixtures module) — one rule definition, checked in two contexts, rather than
 * two hand-written rules that could drift apart.
 */
final class ModuleBoundaryRules {

  private ModuleBoundaryRules() {}

  static ArchRule moduleBoundaryRule(String base, String owner) {
    return noClasses()
        .that()
        .resideOutsideOfPackage(base + "." + owner + "..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(base + "." + owner + ".repository..", base + "." + owner + ".domain..")
        .because("cross-module access must go through the module's ..api.. package only");
  }
}
