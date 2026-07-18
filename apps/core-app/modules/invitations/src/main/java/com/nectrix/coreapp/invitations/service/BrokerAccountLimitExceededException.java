package com.nectrix.coreapp.invitations.service;

/**
 * TICKET-114 — thrown before {@code repository.insert(...)} when an Individual-mode caller (no
 * {@code MASTER}/{@code FOLLOWER} role) has already used up their plan's master-slot or
 * follower-slot capacity ({@code CapabilityLimitsApi}). Never thrown for a real Master/Follower —
 * those aren't subject to this limit at all.
 */
public class BrokerAccountLimitExceededException extends RuntimeException {

  private final String connectionRole;
  private final int limit;

  public BrokerAccountLimitExceededException(String connectionRole, int limit) {
    this.connectionRole = connectionRole;
    this.limit = limit;
  }

  public String connectionRole() {
    return connectionRole;
  }

  public int limit() {
    return limit;
  }
}
