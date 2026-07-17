package com.nectrix.coreapp.auth.service;

/**
 * {@code users.email} is unique — a second self-serve registration for an already-registered
 * address is a clean 409, not a raw {@code DataIntegrityViolationException} (mapped by {@link
 * com.nectrix.coreapp.auth.web.AuthExceptionHandler}). Checked explicitly in {@link
 * RegistrationService} before insert, same "check first, don't rely on the constraint alone"
 * discipline {@code MasterProfileAlreadyExistsException} already established in the social module.
 */
public class EmailAlreadyRegisteredException extends RuntimeException {}
