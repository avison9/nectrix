package com.nectrix.coreapp.social.service;

import java.util.UUID;

/**
 * {@code master_profiles.primary_broker_account_id} is {@code UNIQUE} (TICKET-125 — a user CAN have
 * multiple profiles, one per eligible broker account/strategy, but never two profiles for the SAME
 * account) — a second {@code POST /master-profiles} naming an account that already backs a profile
 * is a 409, not a second row (mapped by {@code MasterProfileExceptionHandler}). Carries the
 * existing profile's id so the frontend can redirect straight to it instead of just showing a bare
 * error.
 */
public class MasterProfileAlreadyExistsException extends RuntimeException {

  private final UUID existingProfileId;

  public MasterProfileAlreadyExistsException(UUID existingProfileId) {
    this.existingProfileId = existingProfileId;
  }

  public UUID existingProfileId() {
    return existingProfileId;
  }
}
