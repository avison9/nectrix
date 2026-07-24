package com.nectrix.coreapp.admin.web;

import com.nectrix.coreapp.admin.service.ServiceControlClient.ServiceControlException;
import java.util.NoSuchElementException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps the plain {@link NoSuchElementException} thrown by both {@code ImpersonationApi} (no such
 * target user) and {@code BrokerAccountLookupApi} (no such broker account) to 404 — both
 * cross-module ..api.. surfaces deliberately throw only this standard JDK type, never a
 * module-internal exception class, so this handler needs no import from either module beyond {@code
 * AdminController} itself.
 */
@RestControllerAdvice(basePackageClasses = AdminController.class)
public class AdminExceptionHandler {

  @ExceptionHandler(NoSuchElementException.class)
  public ResponseEntity<ErrorBody> handleNotFound() {
    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorBody("not_found"));
  }

  /**
   * TICKET-012 — thrown by {@code AdminController#provisionUser} for a {@code role} outside the
   * {ADMIN, SUPPORT} set it accepts (e.g. MASTER — provisioning those is a deferred Phase 1
   * endpoint, {@code POST /api/v1/admin/masters}, since {@code master_profiles} doesn't exist yet).
   */
  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<ErrorBody> handleInvalidRequest() {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorBody("invalid_request"));
  }

  /**
   * TICKET-117 bugfix — thrown by {@code FeeLedgerAdminApi#resolve} when the target row isn't
   * currently DISPUTED (e.g. a second resolve attempt on an already-resolved row, most often from a
   * double-click/resubmit before the UI caught up) — a real 409, not a silent duplicate
   * compensating record.
   */
  @ExceptionHandler(IllegalStateException.class)
  public ResponseEntity<ErrorBody> handleInvalidState() {
    return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorBody("invalid_state"));
  }

  /**
   * Engine Control page — thrown by {@code DockerServiceControlClient} when the underlying {@code
   * docker restart/stop/start} call itself failed (container not found, docker daemon unreachable,
   * non-zero exit) — a real 502, distinct from the 409 a disabled service-control bean produces
   * ({@code IllegalStateException} above), since this means the action was attempted and failed,
   * not merely refused.
   */
  @ExceptionHandler(ServiceControlException.class)
  public ResponseEntity<ErrorBody> handleServiceControlFailure(ServiceControlException e) {
    return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
        .body(new ErrorBody("service_control_failed: " + e.getMessage()));
  }

  public record ErrorBody(String error) {}
}
