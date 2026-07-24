package com.nectrix.coreapp.trading.service;

import com.nectrix.coreapp.social.api.MasterProfileLookupApi;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Feature — resolves "who owns this master profile" for {@code @PostAuthorize} SpEL expressions
 * (referenced by bean name, e.g. {@code @masterProfileOwnership.ownerUserId(...)}), the same
 * runtime-bean-name-lookup convention {@code auth.security.SecurityPermissions}'s own {@code
 * "perms"} bean already establishes — this creates no cross-module Java dependency in the SpEL
 * expression itself, only in this one small resolver class. Exists so {@link
 * CopyRelationshipService#getCopyRelationshipForMaster} can check master-side ownership without
 * widening {@link CopyRelationshipService#getCopyRelationship}'s own follower-only
 * {@code @PostAuthorize} (which every mutation endpoint also relies on as its fetch-then-check
 * gate).
 */
@Component("masterProfileOwnership")
public class MasterProfileOwnership {

  private final MasterProfileLookupApi masterProfileLookupApi;

  public MasterProfileOwnership(MasterProfileLookupApi masterProfileLookupApi) {
    this.masterProfileLookupApi = masterProfileLookupApi;
  }

  public UUID ownerUserId(UUID masterProfileId) {
    return masterProfileLookupApi.getMasterProfile(masterProfileId).userId();
  }
}
