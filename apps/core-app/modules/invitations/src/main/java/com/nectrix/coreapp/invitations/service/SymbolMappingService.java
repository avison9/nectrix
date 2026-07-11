package com.nectrix.coreapp.invitations.service;

import com.nectrix.coreapp.invitations.domain.SymbolMapping;
import com.nectrix.coreapp.invitations.repository.SymbolMappingRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * TICKET-103 — symbol-mapping auto-suggestion + confirmation
 * (nectrix_plan/docs/08-copy-trading-engine.md §8.4).
 *
 * <p>{@link #listMappings}/{@link #confirmMapping} both call {@link
 * BrokerAccountService#getBrokerAccount} FIRST, purely as an ownership-check gate — that method's
 * own {@code @PostAuthorize} (owner-or-staff) throws {@code AccessDeniedException}/{@code
 * BrokerAccountNotFoundException} before this class's own logic runs. This reuses the exact,
 * already-tested TICKET-006 authorization primitive rather than re-implementing an ownership check
 * here: {@code symbol_mappings.broker_account_id} has the same owner as the broker account itself,
 * so "does the caller own this broker account" is exactly "does the caller own this mapping".
 * {@code @PostAuthorize} would be unsafe on a *mutating* method (it runs after the method body — a
 * write could commit before authorization denies it, per that class's own Javadoc), but calling it
 * here as a precondition BEFORE this class's own mutation is not that hazard: nothing in {@code
 * confirmMapping} has written anything yet by the time {@code getBrokerAccount} returns or throws.
 *
 * <p>{@link #suggestMappings} is the internal-endpoint path only (apps/broker-adapters /
 * apps/mt5-bridge-gateway calling in via the shared-secret-authenticated {@code /internal/**}
 * filter chain) — deliberately NO ownership check, matching {@link BrokerAccountInternalService}'s
 * own identical convention: an internal service-to-service call isn't tied to any one end-user's
 * JWT/SecurityContext.
 */
@Service
public class SymbolMappingService {

  private final SymbolMappingRepository repository;
  private final BrokerAccountService brokerAccountService;

  public SymbolMappingService(
      SymbolMappingRepository repository, BrokerAccountService brokerAccountService) {
    this.repository = repository;
    this.brokerAccountService = brokerAccountService;
  }

  public List<SymbolMapping> listMappings(UUID brokerAccountId) {
    brokerAccountService.getBrokerAccount(brokerAccountId); // ownership-check gate, see class doc
    return repository.findByBrokerAccountId(brokerAccountId);
  }

  /**
   * Confirms (or overrides) an existing auto-suggested mapping — {@code brokerSymbolName} is the
   * only field the caller supplies (docs/14-api-specification.md §14.3's own PUT body shape); spec
   * numbers always come from the adapter-verified suggestion, never hand-typed here. Throws {@link
   * SymbolMappingNotFoundException} (404) if no suggested row exists yet for this canonical symbol
   * — this endpoint confirms/edits an existing suggestion, it does not blindly create one for a
   * symbol auto-suggestion never touched.
   */
  public SymbolMapping confirmMapping(
      UUID brokerAccountId,
      String canonicalSymbol,
      String brokerSymbolName,
      UUID confirmedByUserId) {
    brokerAccountService.getBrokerAccount(brokerAccountId); // ownership-check gate, see class doc
    int updated =
        repository.confirm(brokerAccountId, canonicalSymbol, brokerSymbolName, confirmedByUserId);
    if (updated == 0) {
      throw new SymbolMappingNotFoundException();
    }
    return repository
        .findByBrokerAccountIdAndCanonicalSymbol(brokerAccountId, canonicalSymbol)
        .orElseThrow(SymbolMappingNotFoundException::new);
  }

  /** One entry from the internal suggestions POST body — see SymbolMappingInternalController. */
  public record SuggestedMapping(
      String canonicalSymbol,
      String brokerSymbolName,
      double contractSize,
      double lotStep,
      double minLot,
      double maxLot,
      double pipSize,
      short digits,
      String marginCurrency) {}

  public void suggestMappings(UUID brokerAccountId, List<SuggestedMapping> suggestions) {
    for (SuggestedMapping s : suggestions) {
      repository.upsertSuggested(
          brokerAccountId,
          s.canonicalSymbol(),
          s.brokerSymbolName(),
          s.contractSize(),
          s.lotStep(),
          s.minLot(),
          s.maxLot(),
          s.pipSize(),
          s.digits(),
          s.marginCurrency());
    }
  }
}
