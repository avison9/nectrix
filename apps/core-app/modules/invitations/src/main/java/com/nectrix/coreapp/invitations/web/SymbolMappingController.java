package com.nectrix.coreapp.invitations.web;

import com.nectrix.coreapp.invitations.domain.SymbolMapping;
import com.nectrix.coreapp.invitations.service.SymbolMappingService;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * TICKET-103 — symbol-mapping confirmation flow, docs/14-api-specification.md §14.3. Any
 * authenticated user may call this; ownership vs. staff-bypass is enforced by {@link
 * SymbolMappingService}'s own calls into {@code BrokerAccountService#getBrokerAccount} (same
 * pattern {@link BrokerAccountController} uses) — no route-level role check needed here.
 *
 * <p>{@link #confirm} takes {@code Authentication} (not a bare {@code @AuthenticationPrincipal
 * Jwt}) since it needs the subject for {@code confirmed_by_user_id} — {@link #list} needs no
 * identity at all, matching {@link BrokerAccountController#getById}'s own minimal-parameter style.
 */
@RestController
public class SymbolMappingController {

  private final SymbolMappingService service;

  public SymbolMappingController(SymbolMappingService service) {
    this.service = service;
  }

  @GetMapping("/api/v1/broker-accounts/{id}/symbol-mappings")
  public List<SymbolMappingResponse> list(@PathVariable UUID id) {
    return service.listMappings(id).stream().map(SymbolMappingResponse::from).toList();
  }

  @PutMapping("/api/v1/broker-accounts/{id}/symbol-mappings/{canonicalSymbol}")
  public SymbolMappingResponse confirm(
      @PathVariable UUID id,
      @PathVariable String canonicalSymbol,
      Authentication authentication,
      @RequestBody ConfirmRequest request) {
    UUID userId = currentUserId(authentication);
    SymbolMapping mapping =
        service.confirmMapping(id, canonicalSymbol, request.brokerSymbolName(), userId);
    return SymbolMappingResponse.from(mapping);
  }

  private UUID currentUserId(Authentication authentication) {
    return authentication.getPrincipal() instanceof Jwt jwt
        ? UUID.fromString(jwt.getSubject())
        : UUID.fromString(authentication.getName());
  }

  public record ConfirmRequest(String brokerSymbolName) {}

  public record SymbolMappingResponse(
      long id,
      String brokerAccountId,
      String canonicalSymbol,
      String brokerSymbolName,
      double contractSize,
      double lotStep,
      double minLot,
      double maxLot,
      double pipSize,
      short digits,
      String marginCurrency,
      boolean isConfirmed) {
    static SymbolMappingResponse from(SymbolMapping m) {
      return new SymbolMappingResponse(
          m.id(),
          m.brokerAccountId().toString(),
          m.canonicalSymbol(),
          m.brokerSymbolName(),
          m.contractSize(),
          m.lotStep(),
          m.minLot(),
          m.maxLot(),
          m.pipSize(),
          m.digits(),
          m.marginCurrency(),
          m.isConfirmed());
    }
  }
}
