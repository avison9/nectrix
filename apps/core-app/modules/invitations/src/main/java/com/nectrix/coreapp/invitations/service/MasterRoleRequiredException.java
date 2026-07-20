package com.nectrix.coreapp.invitations.service;

/**
 * MASTER_ONLY was requested by a caller who doesn't hold the real, onboarded MASTER role —
 * broadcasting trades to other people's followers requires actually being a vetted Master, not just
 * setting a broker account's own connection_role. Mapped to 403 by {@code
 * BrokerAccountExceptionHandler}.
 */
public class MasterRoleRequiredException extends RuntimeException {}
