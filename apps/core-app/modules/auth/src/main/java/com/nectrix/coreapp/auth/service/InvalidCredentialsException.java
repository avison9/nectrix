package com.nectrix.coreapp.auth.service;

/**
 * Wrong email, password, or TOTP code — deliberately the same exception/response for all three, so
 * a failed login never leaks which part was wrong.
 */
public class InvalidCredentialsException extends RuntimeException {}
