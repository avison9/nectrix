package com.nectrix.coreapp.trading.service;

import com.nectrix.coreapp.invitations.api.BrokerAccountLookupApi;
import java.math.BigDecimal;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Feature — shared by {@link InvitationCopySetupService}/{@link AdminCopyLinkService}, the two real
 * copy-relationship-activation paths. A minimum, when the Master has one configured, is a real
 * business rule that must not silently pass — a failed live balance check propagates (the caller
 * simply fails the request, same as any other live-broker-dependent endpoint in this codebase, e.g.
 * {@code BrokerAccountController#snapshot}). Capturing {@code starting_equity} (the return%
 * anchor), by contrast, is best-effort even when a minimum IS enforced (it's already fetched in
 * that case) or attempted fresh when it isn't — a failed capture just means no return% is available
 * later, never a reason to block activation on its own.
 */
@Service
public class MinFollowerBalanceGate {

  private static final Logger log = LoggerFactory.getLogger(MinFollowerBalanceGate.class);

  private final BrokerAccountLookupApi brokerAccountLookupApi;

  public MinFollowerBalanceGate(BrokerAccountLookupApi brokerAccountLookupApi) {
    this.brokerAccountLookupApi = brokerAccountLookupApi;
  }

  /**
   * @throws InsufficientFollowerBalanceException if {@code minFollowerBalance} is set and the
   *     follower's live balance is below it.
   * @return the follower account's live equity (for {@code starting_equity}), or {@code null} if no
   *     minimum is configured and the best-effort live call failed.
   */
  public BigDecimal checkAndCaptureStartingEquity(
      BigDecimal minFollowerBalance, UUID followerBrokerAccountId) {
    if (minFollowerBalance != null) {
      BrokerAccountLookupApi.AccountBalanceView balance =
          brokerAccountLookupApi.getAccountBalance(followerBrokerAccountId);
      if (balance.balance().compareTo(minFollowerBalance) < 0) {
        throw new InsufficientFollowerBalanceException();
      }
      return balance.equity();
    }
    try {
      return brokerAccountLookupApi.getAccountBalance(followerBrokerAccountId).equity();
    } catch (Exception e) {
      log.warn(
          "could not capture starting_equity for new copy relationship (follower broker account {})",
          followerBrokerAccountId,
          e);
      return null;
    }
  }
}
