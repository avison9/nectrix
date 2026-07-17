package com.nectrix.coreapp.billing.scheduler;

import com.nectrix.coreapp.billing.service.SettlementSchedulerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * TICKET-113 — daily (not literally "monthly") so a relationship's rolling 30-day period and a
 * STOPPED relationship's final pro-rated settlement both get caught promptly; see
 * SettlementSchedulerService's own Javadoc for why daily-checking-for-due-periods is this
 * codebase's chosen interpretation of docs/11-fee-engine-billing.md §11.4's "monthly" cadence.
 * {@code @EnableScheduling} is already active twice over (modules/invitations' InvitationsConfig,
 * bootstrap's CoreAppApplication) — nothing new to enable here.
 */
@Component
public class SettlementScheduler {

  private static final Logger log = LoggerFactory.getLogger(SettlementScheduler.class);

  private final SettlementSchedulerService service;

  public SettlementScheduler(SettlementSchedulerService service) {
    this.service = service;
  }

  @Scheduled(cron = "0 0 1 * * *")
  public void run() {
    try {
      service.runSettlement();
    } catch (Exception e) {
      log.error("billing: settlement run failed", e);
    }
  }
}
