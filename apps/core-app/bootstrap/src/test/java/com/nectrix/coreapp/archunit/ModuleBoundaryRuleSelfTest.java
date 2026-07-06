package com.nectrix.coreapp.archunit;

import static com.nectrix.coreapp.archunit.ModuleBoundaryRules.moduleBoundaryRule;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

/**
 * Proves ModuleBoundaryArchTest's rule actually fires, rather than trusting an unverified
 * assertion. archunit-fixtures (package com.nectrix.fixtures.violating, deliberately outside
 * com.nectrix.coreapp so it can never be swept into the production scan) contains a permanent, real
 * violation of the same rule shape; this test asserts the rule catches it, on every test run — not
 * a one-off manual commit-then-revert.
 */
class ModuleBoundaryRuleSelfTest {

  @Test
  void ruleFailsAgainstADeliberateFixtureViolation() {
    JavaClasses classes = new ClassFileImporter().importPackages("com.nectrix.fixtures.violating");

    ArchRule rule = moduleBoundaryRule("com.nectrix.fixtures.violating", "owner");

    assertThrows(AssertionError.class, () -> rule.check(classes));
  }
}
