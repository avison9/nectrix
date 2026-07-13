package com.nectrix.coreapp.archunit;

import static com.nectrix.coreapp.archunit.ModuleBoundaryRules.moduleBoundaryRule;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/**
 * Production boundary check: no module may reach into another module's repository or domain layer
 * directly. Cross-module reads go through the module's api package; cross-module side effects go
 * through the event bus.
 */
class ModuleBoundaryArchTest {

  private static final String BASE = "com.nectrix.coreapp";
  private static final String[] MODULES = {
    "auth", "invitations", "social", "billing", "admin", "analytics", "notifications", "trading"
  };

  @Test
  void modulesDoNotReachIntoEachOthersRepositoryOrDomainLayer() {
    JavaClasses classes =
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(BASE);

    for (String owner : MODULES) {
      moduleBoundaryRule(BASE, owner).check(classes);
    }
  }
}
