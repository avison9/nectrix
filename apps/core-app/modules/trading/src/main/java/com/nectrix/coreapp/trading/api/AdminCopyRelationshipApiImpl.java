package com.nectrix.coreapp.trading.api;

import com.nectrix.coreapp.social.api.MasterProfileLookupApi;
import com.nectrix.coreapp.social.api.MasterProfileSummaryView;
import com.nectrix.coreapp.trading.domain.CopyRelationship;
import com.nectrix.coreapp.trading.service.AdminCopyLinkService;
import com.nectrix.coreapp.trading.service.BrokerAccountNotOwnedException;
import com.nectrix.coreapp.trading.service.DuplicateCopyRelationshipException;
import com.nectrix.coreapp.trading.service.NoSuchMasterProfileException;
import com.nectrix.coreapp.trading.service.SameBrokerAccountException;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class AdminCopyRelationshipApiImpl implements AdminCopyRelationshipApi {

  private final AdminCopyLinkService adminCopyLinkService;
  private final MasterProfileLookupApi masterProfileLookupApi;

  public AdminCopyRelationshipApiImpl(
      AdminCopyLinkService adminCopyLinkService, MasterProfileLookupApi masterProfileLookupApi) {
    this.adminCopyLinkService = adminCopyLinkService;
    this.masterProfileLookupApi = masterProfileLookupApi;
  }

  @Override
  public LinkedCopyRelationshipView linkFollowerToMaster(
      UUID followerUserId, UUID masterUserId, UUID followerBrokerAccountId) {
    CopyRelationship relationship;
    try {
      relationship =
          adminCopyLinkService.linkFollowerToMaster(
              followerUserId, masterUserId, followerBrokerAccountId);
    } catch (NoSuchMasterProfileException e) {
      throw new NoSuchElementException("No master profile for user " + masterUserId);
    } catch (BrokerAccountNotOwnedException | SameBrokerAccountException e) {
      throw new IllegalArgumentException(e.getClass().getSimpleName());
    } catch (DuplicateCopyRelationshipException e) {
      throw new IllegalStateException(
          "A copy relationship already links these broker accounts");
    }
    MasterProfileSummaryView master =
        masterProfileLookupApi.getMasterProfile(relationship.masterProfileId());
    return new LinkedCopyRelationshipView(
        relationship.id(), relationship.status(), master.displayName());
  }
}
