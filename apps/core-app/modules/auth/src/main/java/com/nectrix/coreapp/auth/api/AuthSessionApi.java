package com.nectrix.coreapp.auth.api;

import java.util.UUID;

/**
 * TICKET-118 — lets {@code invitations}' {@code AcceptInviteController} issue a real session for
 * the (new-or-existing) user behind an accepted invitation, exactly like {@code AuthController
 * #login} does, without importing {@code auth.service}/{@code auth.repository} directly (enforced
 * by ModuleBoundaryArchTest). The one deliberate difference from login: no password check — a
 * validated, single-use invitation token is itself the credential here.
 */
public interface AuthSessionApi {

  TokenPairView issueSession(UUID userId, String deviceInfoJson, String ipAddress);
}
