package com.nectrix.coreapp.trading.service;

/**
 * A non-terminal {@code copy_relationships} row already links this exact master/follower broker
 * account pair — thrown by {@code AdminCopyLinkService} to avoid an accidental duplicate link.
 */
public class DuplicateCopyRelationshipException extends RuntimeException {}
