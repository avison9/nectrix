package com.nectrix.coreapp.invitations.service;

import com.nectrix.coreapp.invitations.client.BrokerAdaptersInternalClient;
import com.nectrix.coreapp.invitations.config.InvitationsProperties;
import com.nectrix.coreapp.invitations.repository.AccountSnapshotRepository;
import com.nectrix.coreapp.invitations.repository.BrokerAccountRepository;
import com.nectrix.coreapp.invitations.repository.BrokerAccountRepository.SnapshotCandidate;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Bugfix — periodically writes a real {@code account_snapshots} row for every CONNECTED broker
 * account behind a Master profile or an ACTIVE/PAUSED copy relationship, reusing the exact same
 * live {@link BrokerAdaptersInternalClient#getAccountSnapshot} call the dashboard's own {@code
 * GET /api/v1/broker-accounts/{id}/snapshot} route already makes. Previously, {@code
 * account_snapshots} was only ever written as a side effect of apps/copy-engine dispatching a
 * copied trade — meaning the equity curve {@code LeaderboardComputationRepository
 * #findDailyEquityCurve} feeds into analytics could be hours-to-days stale, or missing entirely for
 * an account with no recent trade activity, which is why the analytics page's "current equity"
 * could disagree with the dashboard's own live figure. This closes that gap at the data-source
 * level; no change needed to the read side once real, regular samples exist.
 *
 * <p>Every account is processed independently — one account's snapshot failure must never abort
 * the whole batch. {@code @ConditionalOnProperty} (default false, see {@link
 * InvitationsProperties.AccountSnapshot}'s own Javadoc): this bean — and therefore its {@code
 * @Scheduled} method — doesn't even exist unless explicitly enabled.
 */
@Service
@ConditionalOnProperty(
    prefix = "nectrix.invitations.account-snapshot",
    name = "enabled",
    havingValue = "true")
public class AccountSnapshotSchedulerJob {

  private static final Logger log = LoggerFactory.getLogger(AccountSnapshotSchedulerJob.class);

  private final BrokerAccountRepository brokerAccountRepository;
  private final AccountSnapshotRepository accountSnapshotRepository;
  private final BrokerAdaptersInternalClient brokerAdaptersClient;

  public AccountSnapshotSchedulerJob(
      BrokerAccountRepository brokerAccountRepository,
      AccountSnapshotRepository accountSnapshotRepository,
      BrokerAdaptersInternalClient brokerAdaptersClient) {
    this.brokerAccountRepository = brokerAccountRepository;
    this.accountSnapshotRepository = accountSnapshotRepository;
    this.brokerAdaptersClient = brokerAdaptersClient;
  }

  @Scheduled(fixedDelayString = "${nectrix.invitations.account-snapshot.interval-seconds:3600}000")
  public void snapshotConnectedAccounts() {
    List<SnapshotCandidate> candidates = brokerAccountRepository.findSnapshotCandidates();
    for (SnapshotCandidate candidate : candidates) {
      try {
        snapshotOne(candidate);
      } catch (Exception e) {
        // One account's unexpected failure (network blip, transient DB error, broker-side
        // hiccup) must not abort the rest of the batch -- it's simply retried next cycle.
        log.error("account-snapshot: unexpected failure for broker account {}", candidate.id(), e);
      }
    }
  }

  private void snapshotOne(SnapshotCandidate candidate) {
    UUID id = candidate.id();
    BrokerAdaptersInternalClient.AccountSnapshot snapshot;
    try {
      snapshot = brokerAdaptersClient.getAccountSnapshot(candidate.brokerType(), id.toString());
    } catch (Exception e) {
      log.warn("account-snapshot: fetch failed for broker account {}", id, e);
      return;
    }
    accountSnapshotRepository.insertSnapshot(
        id,
        snapshot.balance(),
        snapshot.equity(),
        snapshot.usedMargin(),
        snapshot.freeMargin(),
        snapshot.marginLevelPct());
  }
}
