// Canonical, broker-agnostic domain types shared by apps/web and
// apps/admin-portal for client-side typing and API-contract clarity.
//
// Direct transcription of nectrix_plan/docs/05-domain-model.md §5.3 — the Go
// services' internal types (packages/go-domain) are the runtime source of
// truth; this package exists for frontend DX, not as a second source of truth.

export type BrokerType = "CTRADER" | "MT5" | "MT4";

export interface NormalizedSymbol {
  canonicalCode: string; // platform's own symbol code, e.g. "EURUSD"
  assetClass: "FX" | "INDEX" | "COMMODITY" | "CRYPTO" | "STOCK_CFD";
}

export interface SymbolSpec {
  symbol: NormalizedSymbol;
  brokerSymbolName: string; // e.g. "EURUSD.a"
  contractSize: number; // e.g. 100000 for 1.0 lot FX
  lotStep: number; // e.g. 0.01
  minLot: number;
  maxLot: number;
  pipSize: number; // e.g. 0.0001
  digits: number;
  marginCurrency: string;
}

export interface AccountSnapshot {
  brokerAccountId: string;
  currency: string; // account denomination, e.g. "USD"
  balance: number;
  equity: number;
  usedMargin: number;
  freeMargin: number;
  marginLevelPct: number | null;
  asOf: string; // ISO-8601 timestamp
}

export type TradeDirection = "BUY" | "SELL";

export interface NormalizedPosition {
  brokerPositionId: string;
  symbol: NormalizedSymbol;
  direction: TradeDirection;
  volumeLots: number;
  openPrice: number;
  currentSlPrice: number | null;
  currentTpPrice: number | null;
  openedAt: string;
}

// The event a master's BrokerAdapter emits; input to the Copy Engine
export type TradeEventType =
  | "POSITION_OPENED"
  | "POSITION_MODIFIED"
  | "POSITION_PARTIALLY_CLOSED"
  | "POSITION_CLOSED";

export interface NormalizedTradeEvent {
  eventId: string; // unique, used as idempotency source
  masterBrokerAccountId: string;
  eventType: TradeEventType;
  position: NormalizedPosition;
  closedVolumeLots?: number; // for PARTIALLY_CLOSED / CLOSED
  fillPrice?: number;
  serverTimestamp: string;
  receivedAtGateway: string; // when platform observed it — used for latency metrics
}

// What the Copy Engine computes and hands to a follower's BrokerAdapter
export interface NormalizedOrderRequest {
  idempotencyKey: string; // derived from (eventId, copyRelationshipId)
  followerBrokerAccountId: string;
  symbol: NormalizedSymbol;
  direction: TradeDirection;
  volumeLots: number;
  slPrice: number | null;
  tpPrice: number | null;
  maxSlippagePips: number;
  clientOrderTag: string; // links back to originating master position for audit
}

export interface NormalizedOrderResult {
  success: boolean;
  brokerPositionId?: string;
  filledPrice?: number;
  rejectReason?: string;
  rawBrokerResponse: unknown; // preserved for audit, never parsed by upstream logic
}

// TICKET-012 — apps/core-app's identity/RBAC types (docs/06-database-schema.md
// §6.2's `roles` seed rows: FOLLOWER, MASTER, PARTNER, ADMIN, SUPPORT). Only
// the three Admin-Portal-reachable roles are modeled here — the portal's own
// route guard rejects anyone without one of these (see apps/admin-portal).
export type AdminPortalRole = "ADMIN" | "SUPPORT" | "MASTER";

// Mirrors AdminController.AuditLogPageResponse/AuditLogRepository.AuditLogEntry
// (apps/core-app/modules/admin) — camelCase here even though the wire JSON is
// snake_case (Spring's global SNAKE_CASE Jackson strategy); @nectrix/api-client
// is responsible for the translation, so every other consumer just deals in
// idiomatic TS shapes.
export interface AuditLogEntry {
  id: number;
  actorUserId: string | null;
  actorType: string;
  action: string;
  targetType: string | null;
  targetId: string | null;
  metadataJson: string | null;
  createdAt: string; // ISO-8601
}

export interface AuditLogPage {
  entries: AuditLogEntry[];
  total: number;
  page: number;
  pageSize: number;
}

// TICKET-110 — broker-linking UI & API. Mirrors
// apps/core-app/modules/invitations' BrokerAccount domain/view record.
export type ConnectionRole = "MASTER_ONLY" | "FOLLOWER_ONLY" | "BOTH";

export type ConnectionStatus = "PENDING" | "CONNECTED" | "DEGRADED" | "DISCONNECTED" | "REAUTH_REQUIRED";

export interface BrokerAccountSummary {
  id: string;
  userId: string;
  brokerType: BrokerType;
  brokerAccountLogin: string;
  displayLabel: string | null;
  isDemo: boolean;
  currency: string;
  connectionRole: ConnectionRole;
  openedViaIbLinkId: string | null;
  connectionStatus: ConnectionStatus;
  lastHealthCheckAt: string | null;
}

// Mirrors BrokerAccountAdaptersInternalClient.AccountSnapshot's JSON shape.
export type BrokerAccountSnapshot = AccountSnapshot;

// Mirrors BrokerIbLink.java (docs/07-auth-onboarding-broker-linking.md §7.4) — read-only here,
// creation/management is TICKET-119's own scope.
export interface BrokerIbLink {
  id: string;
  masterProfileId: string;
  brokerType: BrokerType;
  brokerDisplayName: string;
  ibReferralUrlOrCode: string;
  isActive: boolean;
}

// Mirrors SymbolMappingController.SymbolMappingResponse (TICKET-103).
export interface SymbolMappingEntry {
  id: number;
  brokerAccountId: string;
  canonicalSymbol: string;
  brokerSymbolName: string;
  contractSize: number;
  lotStep: number;
  minLot: number;
  maxLot: number;
  pipSize: number;
  digits: number;
  marginCurrency: string;
  isConfirmed: boolean;
}

// Mirrors BrokerAccountOAuthController.CallbackAccount (the cTrader account picker step).
export interface CtraderAccountOption {
  ctidTraderAccountId: number;
  isLive: boolean;
  traderLogin: number;
  brokerTitleShort: string;
}
