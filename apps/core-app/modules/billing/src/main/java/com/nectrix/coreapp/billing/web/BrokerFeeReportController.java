package com.nectrix.coreapp.billing.web;

import com.nectrix.coreapp.billing.domain.BrokerFeeReport;
import com.nectrix.coreapp.billing.service.BrokerFeeReportService;
import com.nectrix.coreapp.billing.service.BrokerFeeReportService.BrokerFeeReportDetail;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * TICKET-120 — Master-scoped {@code BrokerFeeReport} generation/review/send/confirm (docs/14-api-
 * specification.md §14.16), same {@code @PreAuthorize("hasRole('MASTER')")} + ownership-resolved-
 * from-JWT shape {@code InvitationController}/{@code BrokerIbLinkController} already establish.
 */
@RestController
public class BrokerFeeReportController {

  private final BrokerFeeReportService service;

  public BrokerFeeReportController(BrokerFeeReportService service) {
    this.service = service;
  }

  @PostMapping("/api/v1/master/fee-reports")
  @PreAuthorize("hasRole('MASTER')")
  public BrokerFeeReport generate(
      @AuthenticationPrincipal Jwt jwt, @RequestBody GenerateRequest request) {
    return service.generate(
        currentUserId(jwt), request.brokerType(), request.periodStart(), request.periodEnd());
  }

  @GetMapping("/api/v1/master/fee-reports")
  @PreAuthorize("hasRole('MASTER')")
  public List<BrokerFeeReport> list(@AuthenticationPrincipal Jwt jwt) {
    return service.listForMaster(currentUserId(jwt));
  }

  @GetMapping("/api/v1/master/fee-reports/{id}")
  @PreAuthorize("hasRole('MASTER')")
  public BrokerFeeReportDetail getById(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    return service.getForMaster(currentUserId(jwt), id);
  }

  @PostMapping("/api/v1/master/fee-reports/{id}/send")
  @PreAuthorize("hasRole('MASTER')")
  public BrokerFeeReport send(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    return service.send(currentUserId(jwt), id);
  }

  @PostMapping("/api/v1/master/fee-reports/{id}/confirm-deducted")
  @PreAuthorize("hasRole('MASTER')")
  public BrokerFeeReport confirmDeducted(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    return service.confirmDeducted(currentUserId(jwt), id);
  }

  @PostMapping("/api/v1/master/fee-reports/{id}/confirm-paid")
  @PreAuthorize("hasRole('MASTER')")
  public BrokerFeeReport confirmPaid(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    return service.confirmPaid(currentUserId(jwt), id);
  }

  private UUID currentUserId(Jwt jwt) {
    return UUID.fromString(jwt.getSubject());
  }

  public record GenerateRequest(String brokerType, Instant periodStart, Instant periodEnd) {}
}
