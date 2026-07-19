package com.nectrix.coreapp.invitations.service;

/**
 * The calling user has no {@code master_profiles} row — thrown by {@code POST /master/invitations}.
 */
public class MasterProfileRequiredException extends RuntimeException {}
