package com.nectrix.coreapp.auth.service;

/**
 * Covers all three "refresh failed" cases uniformly (unknown token, expired token, and
 * reuse-of-an-already-rotated token) — deliberately a single response shape for all of them.
 * Distinguishing them to the client would hand an attacker a working oracle for "is this session
 * currently being watched/already rotated"; the reuse case's mass revocation already happened as a
 * side effect (see AuthService#refresh) before this is thrown, regardless of what the client sees.
 */
public class InvalidRefreshTokenException extends RuntimeException {}
