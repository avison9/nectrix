package com.nectrix.coreapp.invitations.service;

/**
 * No confirmable {@code symbol_mappings} row for the requested (brokerAccountId, canonicalSymbol)
 * pair — either auto-suggestion never resolved that symbol against this broker, or it's simply
 * unknown. Mapped to 404 by {@code SymbolMappingExceptionHandler}. Mirrors {@link
 * BrokerAccountNotFoundException}'s shape exactly.
 */
public class SymbolMappingNotFoundException extends RuntimeException {}
