package com.nectrix.coreapp.invitations.web;

import com.nectrix.coreapp.invitations.service.SymbolMappingService;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * TICKET-103 — the internal-only HTTP surface apps/broker-adapters and apps/mt5-bridge-gateway (Go)
 * call to persist real, adapter-verified symbol auto-suggestions. Reachable only under {@code
 * /internal/**}, guarded by SecurityConfig's shared-secret-header filter chain (never the normal
 * JWT path) — same convention as {@link BrokerAccountInternalController}, including its
 * {@code @JsonNaming(LowerCamelCaseStrategy)} override (Go's coreappclient packages expect
 * camelCase JSON, not the app-wide snake_case {@code /api/v1/**} convention).
 */
@RestController
public class SymbolMappingInternalController {

  private final SymbolMappingService service;

  public SymbolMappingInternalController(SymbolMappingService service) {
    this.service = service;
  }

  @PostMapping("/internal/broker-accounts/{id}/symbol-mappings/suggestions")
  public void suggest(@PathVariable UUID id, @RequestBody SuggestSymbolMappingsRequest request) {
    service.suggestMappings(
        id, request.suggestions().stream().map(SuggestionBody::toService).toList());
  }

  @JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
  public record SuggestSymbolMappingsRequest(List<SuggestionBody> suggestions) {}

  @JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
  public record SuggestionBody(
      String canonicalSymbol,
      String brokerSymbolName,
      double contractSize,
      double lotStep,
      double minLot,
      double maxLot,
      double pipSize,
      short digits,
      String marginCurrency) {
    SymbolMappingService.SuggestedMapping toService() {
      return new SymbolMappingService.SuggestedMapping(
          canonicalSymbol,
          brokerSymbolName,
          contractSize,
          lotStep,
          minLot,
          maxLot,
          pipSize,
          digits,
          marginCurrency);
    }
  }
}
