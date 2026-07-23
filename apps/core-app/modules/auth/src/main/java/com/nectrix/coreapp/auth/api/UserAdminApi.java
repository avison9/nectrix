package com.nectrix.coreapp.auth.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * TICKET-117 — admin user search/detail/suspend/reinstate, the cross-module surface {@code admin}
 * module's {@code AdminController} calls into rather than reaching {@code auth.repository}/{@code
 * auth.domain} directly (enforced by {@code ModuleBoundaryArchTest}).
 */
public interface UserAdminApi {

  /**
   * @param status the Users page's own status filter — {@code ACTIVE}/{@code SUSPENDED}/{@code
   *     DELETED}, or blank/null for the default view. See {@code UserRepository#search}'s own
   *     Javadoc for the default view's exact semantics.
   */
  List<UserView> search(String query, String status, int page, int pageSize);

  /**
   * @throws java.util.NoSuchElementException if no such user exists.
   */
  UserView getUser(UUID id);

  /**
   * #421 — lets {@code admin}'s new manual-link endpoint resolve a would-be Master by the email an
   * admin types in, without importing {@code auth.repository} directly. Empty if no such user.
   */
  Optional<UserView> findByEmail(String email);

  /**
   * @param status one of {@code ACTIVE}/{@code SUSPENDED}/{@code DELETED} (the {@code users.status}
   *     CHECK constraint, 002-identity-access.sql) — callers (the admin suspend/reinstate
   *     endpoints) are responsible for only ever passing ACTIVE/SUSPENDED.
   */
  void updateStatus(UUID id, String status);
}
