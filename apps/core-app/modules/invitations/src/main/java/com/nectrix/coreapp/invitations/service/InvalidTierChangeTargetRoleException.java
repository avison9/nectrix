package com.nectrix.coreapp.invitations.service;

/** TICKET-122 — {@code targetRole} was neither {@code MASTER} nor {@code FOLLOWER}. */
public class InvalidTierChangeTargetRoleException extends RuntimeException {}
