package com.nectrix.coreapp.notifications.service;

/**
 * TICKET-115 — {@code drawdown.threshold_breached} can never have its {@code IN_APP} channel
 * disabled (the one channel with no delivery-provider dependency, always reachable) — a risk-safety
 * notification, never fully user-suppressible below this floor. Mapped to 400 by {@code
 * NotificationPreferenceExceptionHandler}.
 */
public class DrawdownFloorViolationException extends RuntimeException {}
