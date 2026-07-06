package com.nectrix.fixtures.violating.offender;

import com.nectrix.fixtures.violating.owner.repository.OwnerRepository;

/**
 * Deliberately reaches into another module's repository package directly — exactly the pattern
 * ModuleBoundaryArchTest forbids in the real module tree. Exists so ModuleBoundaryRuleSelfTest can
 * prove the rule actually fires.
 */
public class OffendingService {
  private final OwnerRepository ownerRepository;

  public OffendingService(OwnerRepository ownerRepository) {
    this.ownerRepository = ownerRepository;
  }

  public String delegate() {
    return ownerRepository.findSomething();
  }
}
