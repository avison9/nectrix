package com.nectrix.coreapp.invitations.service;

/** TICKET-122 — an approve/reject call against a request that's already been decided. */
public class TierChangeRequestNotPendingException extends RuntimeException {}
