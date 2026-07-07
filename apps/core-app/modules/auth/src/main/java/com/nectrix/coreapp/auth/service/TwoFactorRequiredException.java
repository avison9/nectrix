package com.nectrix.coreapp.auth.service;

/**
 * Password was correct but the account has 2FA enabled and no (or an unchecked) totp_code was
 * submitted — client should resubmit with one.
 */
public class TwoFactorRequiredException extends RuntimeException {}
