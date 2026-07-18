package com.nectrix.coreapp.analytics.service;

/**
 * This module's own copy, not {@code social.service.MasterProfileNotFoundException} — {@code
 * modules/analytics} deliberately has no project() dependency on {@code modules/social} (see this
 * module's build.gradle.kts comment); it re-derives ownership via its own raw-SQL lookup instead.
 */
public class MasterProfileNotFoundException extends RuntimeException {}
