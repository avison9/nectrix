package com.nectrix.coreapp.billing.service;

/** No active/trialing/past-due subscription row owned by the caller matches the requested id. */
public class SubscriptionNotFoundException extends RuntimeException {}
