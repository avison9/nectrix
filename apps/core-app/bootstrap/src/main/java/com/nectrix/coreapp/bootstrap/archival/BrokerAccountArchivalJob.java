package com.nectrix.coreapp.bootstrap.archival;

import com.nectrix.coreapp.invitations.api.BrokerAccountArchivalApi;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * TICKET-101 follow-up — the scheduled counterpart to the on-demand archive-and-delete endpoint,
 * same {@link BrokerAccountArchivalOrchestrator#archiveAndDelete} reused by both, so a stale
 * account no longer needs a human to notice and click Delete.
 *
 * <p>Every account is processed independently — one account's archival failure must never abort the
 * whole sweep. Same {@code @ConditionalOnProperty}/per-item try-catch/log-and-continue shape as
 * {@code invitations.service.TokenRefreshJob} (default disabled — see application.yml's own comment
 * for why).
 */
@Service
@ConditionalOnProperty(prefix = "nectrix.archival", name = "enabled", havingValue = "true")
public class BrokerAccountArchivalJob {

  private static final Logger log = LoggerFactory.getLogger(BrokerAccountArchivalJob.class);

  private final BrokerAccountArchivalApi brokerAccountArchivalApi;
  private final BrokerAccountArchivalOrchestrator orchestrator;
  private final ArchivalJobProperties jobProperties;

  public BrokerAccountArchivalJob(
      BrokerAccountArchivalApi brokerAccountArchivalApi,
      BrokerAccountArchivalOrchestrator orchestrator,
      ArchivalJobProperties jobProperties) {
    this.brokerAccountArchivalApi = brokerAccountArchivalApi;
    this.orchestrator = orchestrator;
    this.jobProperties = jobProperties;
  }

  @Scheduled(fixedDelayString = "${nectrix.archival.sweep-interval-seconds:86400}000")
  public void sweepStaleDisconnectedAccounts() {
    List<UUID> candidates =
        brokerAccountArchivalApi.findStaleDisconnected(
            Duration.ofSeconds(jobProperties.staleAfterSeconds()));
    for (UUID brokerAccountId : candidates) {
      try {
        orchestrator.archiveAndDelete(brokerAccountId);
        log.info("archival-sweep: archived and deleted broker account {}", brokerAccountId);
      } catch (Exception e) {
        // One account's unexpected failure (blob upload blip, transient DB error) must not
        // abort the rest of the sweep — it's simply retried on the next scheduled run.
        log.error("archival-sweep: unexpected failure for broker account {}", brokerAccountId, e);
      }
    }
  }
}
