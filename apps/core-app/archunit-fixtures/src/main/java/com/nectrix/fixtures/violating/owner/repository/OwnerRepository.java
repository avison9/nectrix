package com.nectrix.fixtures.violating.owner.repository;

/** Stand-in for a module's repository layer, which only the module's own package may access. */
public interface OwnerRepository {
  String findSomething();
}
