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
  // Pre-formatted "1:N" ratio, e.g. "1:500" — empty for MT4/MT5 (no leverage field in that wire
  // protocol yet, would need an EA-side change), real for cTrader (ProtoOATrader.leverageInCents).
  leverage: string;
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
  // The price this position would realize at if closed right now — null means no live tick is
  // cached yet for this symbol (never a fabricated value), always null for MT4/MT5 today (no live
  // spot-tick plumbing).
  currentPrice: number | null;
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
  // TICKET-101/102 follow-up — brokerType (CTRADER/MT5/MT4) is the platform, not the broker's own
  // brand name (e.g. "Pepperstone"). Both nullable: cTrader has no "server" concept, and accounts
  // linked before this field existed have neither populated.
  brokerName: string | null;
  serverName: string | null;
}

// Mirrors BrokerAccountAdaptersInternalClient.AccountSnapshot's JSON shape.
export type BrokerAccountSnapshot = AccountSnapshot;

// Mirrors BrokerIbLink.java (docs/07-auth-onboarding-broker-linking.md §7.4). TICKET-119 added the
// real create/list/deactivate write path.
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
  // Bugfix — lets the picker mark/disable accounts already linked to this user, so re-running
  // this flow to link a second/third account can't silently re-select an already-linked one.
  alreadyLinked: boolean;
}

// TICKET-111 — Master Profile Creation & CopyRelationship State Machine.
// Mirrors MasterProfileController's response record (apps/core-app/modules/social).
// Matches master_profiles/copy_relationships' own fee_collection_method CHECK constraint exactly
// (005-social-marketplace.sql/006-copy-trading.sql) — NOT an arbitrary frontend-invented enum.
export type FeeCollectionMethod = "BROKER_PARTNERSHIP" | "STRIPE_INVOICE";

export interface MasterProfile {
  id: string;
  userId: string;
  primaryBrokerAccountId: string;
  displayName: string;
  bio: string | null;
  strategyTags: string[];
  performanceFeePercent: number;
  feeCollectionMethod: FeeCollectionMethod;
  isPublic: boolean;
  verifiedAt: string | null;
  createdAt: string;
  // Feature — the minimum broker-account balance a Follower must have to start copying this
  // Master. Null means no minimum.
  minFollowerBalance: number | null;
}

// Bugfix — mirrors bootstrap.archival's PrimaryBrokerAccountChange (social.api), the
// PATCH /master-profiles/{id}/primary-broker-account response.
export interface PrimaryBrokerAccountChange {
  masterProfileId: string;
  oldBrokerAccountId: string;
  newBrokerAccountId: string;
}

// Mirrors CopyRelationshipController.CopyRelationshipView's nested shapes
// (apps/core-app/modules/trading) — the state machine itself lives in
// CopyRelationshipService's Javadoc, transcribed for apps/web's own reference:
// PENDING_RISK_ACK -> [PENDING_AGREEMENT if BROKER_PARTNERSHIP, else ACTIVE] -> ACTIVE <-> PAUSED -> STOPPED.
export type CopyRelationshipStatus =
  | "PENDING_RISK_ACK"
  | "PENDING_AGREEMENT"
  | "ACTIVE"
  | "PAUSED"
  | "STOPPED";

export type CopyDirection = "COPY" | "REVERSE";

export interface MoneyManagementProfileView {
  method: string;
  fixedLotSize: number | null;
  multiplier: number | null;
  riskPercent: number | null;
  roundingMode: string;
}

export interface RiskProfileView {
  maxLotPerTrade: number | null;
  maxOpenPositions: number | null;
  maxSlippagePips: number;
  drawdownPausePct: number | null;
  drawdownCloseAllPct: number | null;
}

export interface CopyRelationship {
  id: string;
  masterProfileId: string;
  followerBrokerAccountId: string;
  status: CopyRelationshipStatus;
  moneyManagementProfile: MoneyManagementProfileView;
  riskProfile: RiskProfileView;
  copyDirection: CopyDirection;
  feeCollectionMethod: FeeCollectionMethod;
  originatingInvitationId: string | null;
  originatingFollowRequestId: string | null;
  highWaterMark: number | null;
  createdAt: string;
  // Feature — a Follower-editable per-relationship symbol EXCLUSION list, empty by default
  // (meaning "copy every symbol this Master trades", unchanged from before this field existed).
  // Enforced in apps/copy-engine, only against new position opens — see that service's own
  // dispatch.go comment for why an already-copied position must still close normally even after
  // its symbol is added here.
  excludedSymbols: string[];
  // Bugfix — null means the server-side lookup missed (deleted profile/user); callers fall back
  // to the id itself rather than showing a blank label.
  masterDisplayName: string | null;
  followerDisplayName: string | null;
  // Feature — % return on the follower's account since this relationship began. Only ever
  // populated for the Master's own followers list/detail view; null means unavailable (no
  // starting_equity anchor, or a live-lookup miss) — never a balance/equity value itself.
  returnPct: number | null;
}

// Mirrors CopyRelationshipController.TradesPage/CopiedTrade (read-only trades-history view).
export type CopiedTradeStatus =
  | "PENDING"
  | "SUBMITTED"
  | "FILLED"
  | "PARTIALLY_CLOSED"
  | "CLOSED"
  | "REJECTED"
  | "FAILED";

export interface CopiedTrade {
  id: string;
  copyRelationshipId: string;
  tradeSignalId: string;
  status: CopiedTradeStatus;
  // TICKET-116 — not a copied_trades column itself, joined in from trade_signals (see
  // CopiedTradeRepository's own Javadoc).
  canonicalSymbol: string;
  // Bugfix — the Trade History page's TYPE column (BUY/SELL), same trade_signals join.
  direction: TradeDirection;
  computedVolumeLots: number;
  requestedPrice: number | null;
  filledPrice: number | null;
  slippagePips: number | null;
  rejectReason: string | null;
  realizedPnl: number | null;
  // Only meaningful for an OPEN/PARTIALLY_CLOSED row — null means either the trade is CLOSED
  // (use realizedPnl instead) or a live price genuinely wasn't available this page load, never a
  // fabricated 0.
  unrealizedPnl: number | null;
  openedAt: string | null;
  closedAt: string | null;
  createdAt: string;
}

export interface CopiedTradesPage {
  trades: CopiedTrade[];
  total: number;
  page: number;
  pageSize: number;
}

// TICKET-112 — public discovery (leaderboard + public master profile). Mirrors
// DiscoveryRepository.LeaderboardEntry/MasterPublicProfile (apps/core-app/modules/social).
export type LeaderboardPeriod = "7D" | "30D" | "90D" | "YTD" | "ALL";

export type LeaderboardSort = "return_pct" | "max_drawdown_pct" | "follower_count" | "sharpe_like_ratio";

export interface LeaderboardEntry {
  masterProfileId: string;
  displayName: string;
  strategyTags: string[];
  returnPct: number;
  maxDrawdownPct: number;
  winRatePct: number | null;
  sharpeLikeRatio: number | null;
  followerCount: number;
  aumProxy: number | null;
}

export interface PublicMasterProfile {
  id: string;
  displayName: string;
  bio: string | null;
  strategyTags: string[];
  performanceFeePercent: number;
  feeCollectionMethod: FeeCollectionMethod;
  verifiedAt: string | null;
  metricsByPeriod: Partial<Record<LeaderboardPeriod, LeaderboardEntry>>;
}

// TICKET-117 — admin MVP (System Health + User Management + Disputes). Mirrors
// apps/core-app/modules/admin's AdminController/AdminRepository/UserAdminApi/FeeLedgerAdminApi
// response records.

export type UserStatus = "ACTIVE" | "SUSPENDED" | "DELETED";

// Mirrors auth.api.UserView.
export interface UserSummary {
  id: string;
  email: string;
  displayName: string;
  twoFactorEnabled: boolean;
  status: UserStatus;
  createdAt: string; // ISO-8601
}

// Mirrors AdminController.UserDetailResponse.
export interface UserDetail {
  user: UserSummary;
  brokerAccounts: BrokerAccountSummary[];
  // Bugfix follow-up — this user's own MT4/MT5 accounts joined against their live pod status
  // (see MtTerminalsSection below), so support/admin can troubleshoot a specific user's terminal
  // from their own detail page instead of cross-referencing system-health by hand.
  // reachable=true with an empty terminals list means this user has no MT4/MT5 accounts at all —
  // distinct from reachable=false (mt-terminal-host itself unreachable).
  mtTerminals: MtTerminalsSection;
}

export type FeeLedgerStatus =
  | "PENDING"
  | "INVOICED"
  | "PAID"
  | "REPORTED_TO_BROKER"
  | "BROKER_CONFIRMED_DEDUCTED"
  | "BROKER_CONFIRMED_PAID"
  | "DISPUTED"
  | "VOID";

// Mirrors billing.api.FeeLedgerAdminApi.FeeLedgerSummaryView.
export interface FeeLedgerEntry {
  id: string;
  copyRelationshipId: string;
  periodStart: string; // ISO-8601
  periodEnd: string; // ISO-8601
  masterFeeAmount: number;
  platformTakeAmount: number;
  netToMasterAmount: number;
  status: FeeLedgerStatus;
}

// TICKET-120 — mirrors billing.domain.BrokerFeeReport.
export type BrokerFeeReportStatus = "DRAFT" | "SENT" | "BROKER_CONFIRMED_DEDUCTED" | "BROKER_CONFIRMED_PAID" | "FAILED";

export interface BrokerFeeReport {
  id: string;
  masterProfileId: string;
  brokerType: BrokerType;
  periodStart: string;
  periodEnd: string;
  status: BrokerFeeReportStatus;
  reportObjectKey: string;
  sentAt: string | null;
  confirmedDeductedAt: string | null;
  confirmedPaidAt: string | null;
  generatedByUserId: string;
  createdAt: string;
}

// Mirrors billing.domain.BrokerFeeReportLine.
export interface BrokerFeeReportLine {
  id: number;
  brokerFeeReportId: string;
  performanceFeeLedgerId: string;
  followerBrokerAccountLogin: string;
  feeAmount: number;
  currency: string;
}

// Mirrors billing.service.BrokerFeeReportService.BrokerFeeReportDetail.
export interface BrokerFeeReportDetail {
  report: BrokerFeeReport;
  lines: BrokerFeeReportLine[];
  documentUrl: string;
}

// Mirrors billing.api.FeeLedgerAdminApi.FeeLedgerDetailView — computationDetailJson is the raw
// JSON text (self-contained by design, see SettlementComputation's own Javadoc); render it as a
// real line-item breakdown, not a passthrough JSON dump.
export interface FeeLedgerDetail {
  id: string;
  copyRelationshipId: string;
  periodStart: string;
  periodEnd: string;
  startingHwm: number;
  endingEquity: number;
  newProfitAboveHwm: number;
  masterFeeAmount: number;
  platformTakeAmount: number;
  netToMasterAmount: number;
  computationDetailJson: string;
  status: FeeLedgerStatus;
}

// Mirrors billing.api.FeeLedgerAdminApi.UnderlyingTradeView.
export interface FeeLedgerUnderlyingTrade {
  id: string;
  canonicalSymbol: string;
  direction: "BUY" | "SELL";
  computedVolumeLots: number;
  status: CopiedTradeStatus;
  realizedPnl: number | null;
  openedAt: string | null;
  closedAt: string | null;
}

// Mirrors AdminController.FeeLedgerDetailResponse.
export interface FeeLedgerDetailPage {
  ledger: FeeLedgerDetail;
  trades: FeeLedgerUnderlyingTrade[];
}

export type FeeLedgerResolution = "UPHOLD" | "ADJUST" | "VOID";

// Mirrors admin.repository.AdminRepository.BrokerConnectionCount.
export interface BrokerConnectionCount {
  brokerType: BrokerType;
  connectionStatus: ConnectionStatus;
  count: number;
}

// Mirrors AdminController.CopyEngineHealth.
export interface CopyEngineHealth {
  windowMinutes: number;
  tradesInWindow: number;
  failedInWindow: number;
}

// Mirrors admin.service.KafkaConsumerLagService.ConsumerGroupLag. messagesReceived is topic-wide
// (independent of any one consumer group); messagesProcessed is this group's own committed
// progress against that topic. Both -1 alongside lag on a failed lookup (see the Java Javadoc).
export interface ConsumerGroupLag {
  groupId: string;
  topic: string;
  lag: number;
  messagesReceived: number;
  messagesProcessed: number;
}

// TICKET-123 — mirrors AdminController.TerminalHealthView. podProvisioned=false means no live
// pod exists for this account at all (never provisioned, or torn down) — every pod* field is
// null in that case, not a fabricated "unhealthy" value.
export interface TerminalHealthView {
  brokerAccountId: string;
  brokerType: string;
  brokerAccountLogin: string;
  connectionStatus: string;
  podProvisioned: boolean;
  podPhase: string | null;
  podReady: boolean | null;
  podRestartCount: number | null;
  podWaitingReason: string | null;
  podLastTransitionTime: string | null;
}

// Mirrors AdminController.MtTerminalsSection. reachable=false means mt-terminal-host itself
// couldn't be reached (distinct from "reachable, zero terminals" — terminals is empty in both
// cases, so always check reachable first, never infer service health from list emptiness).
export interface MtTerminalsSection {
  reachable: boolean;
  terminals: TerminalHealthView[];
}

// Mirrors AdminController.SystemHealthResponse.
export interface SystemHealthSnapshot {
  brokerConnections: BrokerConnectionCount[];
  copyEngine: CopyEngineHealth;
  reconciliationDriftLastHour: number;
  kafkaConsumerLag: ConsumerGroupLag[];
  mtTerminals: MtTerminalsSection;
}

// Engine Control page — mirrors AdminController.EngineStatusView. status is one of
// "CONNECTED"/"IDLE"/"STALE"/"DISCONNECTED" for broker-adapters/copy-engine/mt5-bridge-gateway, or
// just "CONNECTED"/"DISCONNECTED" for mt-terminal-host/redis — connectedCount/lastReconcileAt/
// latencyMs are each null whenever they don't apply to that engine's own state model.
export type EngineStatusState = "CONNECTED" | "IDLE" | "STALE" | "DISCONNECTED";

export interface EngineStatus {
  serviceId: string;
  displayName: string;
  status: EngineStatusState;
  connectedCount: number | null;
  lastReconcileAt: string | null;
  latencyMs: number | null;
}

// TICKET-118 — Invitation System (Master invites a Follower). Mirrors
// apps/core-app/modules/invitations's Invitation domain record / InvitationController /
// PublicInvitationController, and modules/trading's InvitationCopySetupController.

export type InvitationStatus = "PENDING" | "ACCEPTED" | "EXPIRED" | "REVOKED";

// Mirrors invitations.domain.Invitation — never carries the raw token, only its hash (see that
// record's own Javadoc for why).
export interface Invitation {
  id: string;
  masterProfileId: string;
  invitedEmail: string;
  tokenHash: string;
  status: InvitationStatus;
  suggestedBrokerIbLinkId: string | null;
  suggestedMoneyManagementProfileId: string | null;
  suggestedRiskProfileId: string | null;
  createdByUserId: string;
  expiresAt: string;
  acceptedAt: string | null;
  acceptedByUserId: string | null;
  createdAt: string;
  resendCount: number;
  lastResentAt: string | null;
}

// Mirrors PublicInvitationController.InvitationPreview — the public by-token accept-screen data.
export interface InvitationPreview {
  id: string;
  invitedEmail: string;
  masterDisplayName: string | null;
  expiresAt: string;
}

// Mirrors InvitationCopySetupService.PendingInvitation.
export interface PendingInvitation {
  invitationId: string;
  masterDisplayName: string;
  suggestedMoneyManagementProfileId: string | null;
  suggestedRiskProfileId: string | null;
}

// TICKET-118 follow-up — the "Follower refers a prospect, lands in their Master's inbox, Master
// sends a real invitation" flow. Mirrors ProspectNominationController.NominationResponse
// (apps/core-app/modules/trading).
export type ProspectNominationStatus = "PENDING" | "INVITED" | "DISMISSED";

export interface ProspectNomination {
  id: string;
  prospectEmail: string;
  status: ProspectNominationStatus;
  invitationId: string | null;
  nominatedByDisplayName: string | null;
  createdAt: string;
  decidedAt: string | null;
}

// The Follower's own referral-history view — a richer, honest status than the raw DB enum above
// (JOINED reflects the linked invitation's real acceptance, not a guess). Mirrors
// ProspectNominationController.MyNominationResponse.
export type MyProspectNominationStatus = "SENT" | "INVITED" | "JOINED" | "DISMISSED";

export interface MyProspectNomination {
  id: string;
  prospectEmail: string;
  status: MyProspectNominationStatus;
  createdAt: string;
  decidedAt: string | null;
}

// TICKET-122 — mirrors invitations.domain.TierChangeRequest /
// invitations.api.TierChangeRequestAdminApi.TierChangeRequestView (both shapes are identical on
// the wire; the admin view is just reachable by id rather than only "my own").
export type TierChangeTargetRole = "MASTER" | "FOLLOWER";
export type TierChangeRequestStatus = "PENDING" | "APPROVED" | "REJECTED";

export interface TierChangeRequest {
  id: string;
  userId: string;
  targetRole: TierChangeTargetRole;
  status: TierChangeRequestStatus;
  agreementVersion: string;
  agreementAcceptedAt: string;
  reviewedByUserId: string | null;
  reviewReason: string | null;
  reviewedAt: string | null;
  createdAt: string;
}

// #421 — admin manual follower-master linking. Mirrors
// AdminController.LinkFollowerToMasterResponse.
export interface LinkFollowerToMasterResult {
  copyRelationshipId: string;
  status: string;
  masterDisplayName: string;
}
