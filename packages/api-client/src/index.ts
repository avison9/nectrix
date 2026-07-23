// TICKET-012 — the first real client here (everything before this was a
// stub). Covers only what apps/admin-portal needs so far: login/refresh and
// the two new admin routes. Every function is meant to be called
// server-side only (Next.js Route Handlers) — the access token these return
// must never reach client JS, see apps/admin-portal's app/api/auth routes.

import type {
  AdminPortalRole,
  AuditLogEntry,
  AuditLogPage,
  BrokerAccountSnapshot,
  BrokerAccountSummary,
  BrokerConnectionCount,
  BrokerIbLink,
  BrokerType,
  CopiedTrade,
  CopiedTradesPage,
  CopiedTradeStatus,
  ConnectionRole,
  BrokerFeeReport,
  BrokerFeeReportDetail,
  ConnectionStatus,
  ConsumerGroupLag,
  CopyEngineHealth,
  CopyRelationship,
  CopyRelationshipStatus,
  CtraderAccountOption,
  FeeCollectionMethod,
  FeeLedgerDetail,
  FeeLedgerDetailPage,
  FeeLedgerEntry,
  FeeLedgerResolution,
  FeeLedgerStatus,
  FeeLedgerUnderlyingTrade,
  Invitation,
  InvitationPreview,
  LeaderboardEntry,
  LeaderboardPeriod,
  LeaderboardSort,
  LinkFollowerToMasterResult,
  MasterProfile,
  MyProspectNomination,
  NormalizedPosition,
  NormalizedTradeEvent,
  PendingInvitation,
  PrimaryBrokerAccountChange,
  ProspectNomination,
  PublicMasterProfile,
  SymbolMappingEntry,
  SystemHealthSnapshot,
  TierChangeRequest,
  TierChangeRequestStatus,
  TierChangeTargetRole,
  UserDetail,
  UserStatus,
  UserSummary,
} from "@nectrix/domain-model";

export type {
  NormalizedTradeEvent,
  AdminPortalRole,
  AuditLogEntry,
  AuditLogPage,
  BrokerAccountSnapshot,
  BrokerAccountSummary,
  BrokerConnectionCount,
  BrokerIbLink,
  BrokerType,
  CopiedTrade,
  CopiedTradesPage,
  CopiedTradeStatus,
  ConnectionRole,
  BrokerFeeReport,
  BrokerFeeReportDetail,
  ConnectionStatus,
  ConsumerGroupLag,
  CopyEngineHealth,
  CopyRelationship,
  CopyRelationshipStatus,
  CtraderAccountOption,
  FeeCollectionMethod,
  FeeLedgerDetail,
  FeeLedgerDetailPage,
  FeeLedgerEntry,
  FeeLedgerResolution,
  FeeLedgerStatus,
  FeeLedgerUnderlyingTrade,
  Invitation,
  InvitationPreview,
  LeaderboardEntry,
  LeaderboardPeriod,
  LeaderboardSort,
  LinkFollowerToMasterResult,
  MasterProfile,
  MyProspectNomination,
  PendingInvitation,
  PrimaryBrokerAccountChange,
  ProspectNomination,
  PublicMasterProfile,
  SymbolMappingEntry,
  SystemHealthSnapshot,
  TierChangeRequest,
  TierChangeRequestStatus,
  TierChangeTargetRole,
  UserDetail,
  UserStatus,
  UserSummary,
};

export const API_CLIENT_VERSION = "0.2.0";

export class ApiError extends Error {
  constructor(
    public readonly status: number,
    public readonly body: unknown,
  ) {
    super(`core-app request failed with status ${status}`);
  }
}

function snakeToCamel(key: string): string {
  return key.replace(/_([a-z0-9])/g, (_match, c: string) => c.toUpperCase());
}

// core-app's Jackson config (spring.jackson.property-naming-strategy:
// SNAKE_CASE) means every JSON response has snake_case keys; this package
// hands back idiomatic camelCase TS shapes so nothing downstream has to know
// that. Fully recursive (objects nested inside objects, arrays of objects,
// objects containing arrays, any depth) — TICKET-111's CopyRelationshipView
// (nested moneyManagementProfile/riskProfile) and TICKET-112's
// PublicMasterProfile (a map of nested LeaderboardEntry objects) both need
// more than one level. A prior version of this function only recursed into
// arrays, silently leaving every underscored key inside a plain nested object
// (e.g. moneyManagementProfile.fixed_lot_size) unconverted — caught while
// designing TICKET-112's own doubly-nested response shape, fixed here.
function mapKeysShallow<T>(value: unknown): T {
  if (Array.isArray(value)) {
    return value.map((item) => mapKeysShallow(item)) as unknown as T;
  }
  if (value !== null && typeof value === "object") {
    const result: Record<string, unknown> = {};
    for (const [key, val] of Object.entries(value as Record<string, unknown>)) {
      result[snakeToCamel(key)] = mapKeysShallow(val);
    }
    return result as T;
  }
  return value as T;
}

async function coreAppFetch<T>(
  baseUrl: string,
  path: string,
  init: RequestInit & { accessToken?: string } = {},
): Promise<T> {
  const { accessToken, ...rest } = init;
  const headers = new Headers(rest.headers);
  headers.set("Content-Type", "application/json");
  if (accessToken) {
    headers.set("Authorization", `Bearer ${accessToken}`);
  }
  const response = await fetch(`${baseUrl}${path}`, { ...rest, headers });
  const text = await response.text();
  const body = text ? JSON.parse(text) : null;
  if (!response.ok) {
    throw new ApiError(response.status, body);
  }
  return mapKeysShallow<T>(body);
}

export interface LoginResult {
  accessToken: string;
  refreshToken: string;
  expiresIn: number;
}

export async function login(
  baseUrl: string,
  email: string,
  password: string,
  totpCode?: string,
): Promise<LoginResult> {
  return coreAppFetch<LoginResult>(baseUrl, "/api/v1/auth/login", {
    method: "POST",
    body: JSON.stringify({ email, password, ...(totpCode ? { totp_code: totpCode } : {}) }),
  });
}

export async function refreshSession(baseUrl: string, refreshToken: string): Promise<LoginResult> {
  return coreAppFetch<LoginResult>(baseUrl, "/api/v1/auth/refresh", {
    method: "POST",
    body: JSON.stringify({ refresh_token: refreshToken }),
  });
}

export interface TwoFactorEnrollment {
  secret: string;
  qrCodeUri: string; // data:image/png;base64,... -- ready to drop straight into an <img src>
}

/**
 * TICKET-005's /2fa/enable — safe to call repeatedly (TwoFactorService's own
 * design: each call overwrites the prior pending secret server-side, so a
 * page reload mid-enrollment just means re-scanning, never a broken state).
 */
export async function beginTwoFactorEnrollment(
  baseUrl: string,
  accessToken: string,
): Promise<TwoFactorEnrollment> {
  return coreAppFetch<TwoFactorEnrollment>(baseUrl, "/api/v1/auth/2fa/enable", {
    method: "POST",
    accessToken,
  });
}

/** TICKET-005's /2fa/verify — 204 on success, flips users.two_factor_enabled server-side. */
export async function verifyTwoFactor(
  baseUrl: string,
  accessToken: string,
  totpCode: string,
): Promise<void> {
  await coreAppFetch<null>(baseUrl, "/api/v1/auth/2fa/verify", {
    method: "POST",
    accessToken,
    body: JSON.stringify({ totp_code: totpCode }),
  });
}

/**
 * TICKET-117 bugfix — the real /2fa/disable endpoint (was permanently a dead button on the
 * frontend). 204 on success; a rejected (wrong/expired) TOTP code throws ApiError with status 422,
 * same shape as {@link verifyTwoFactor}.
 */
export async function disableTwoFactor(
  baseUrl: string,
  accessToken: string,
  totpCode: string,
): Promise<void> {
  await coreAppFetch<null>(baseUrl, "/api/v1/auth/2fa/disable", {
    method: "POST",
    accessToken,
    body: JSON.stringify({ totp_code: totpCode }),
  });
}

export interface ProvisionedUser {
  id: string;
}

/**
 * ADMIN-only server-side call (TICKET-012's account-provisioning form). SUPER_ADMIN/PARTNER are
 * deliberately not provisionable this way — see AdminController#PROVISIONABLE_ROLES's own Javadoc.
 */
export async function createAdminUser(
  baseUrl: string,
  accessToken: string,
  input: {
    email: string;
    password: string;
    displayName: string;
    role: "ADMIN" | "SUPPORT" | "MASTER" | "FOLLOWER";
  },
): Promise<ProvisionedUser> {
  return coreAppFetch<ProvisionedUser>(baseUrl, "/api/v1/admin/users", {
    method: "POST",
    accessToken,
    body: JSON.stringify({
      email: input.email,
      password: input.password,
      display_name: input.displayName,
      role: input.role,
    }),
  });
}

export interface AuditLogFilter {
  actorUserId?: string;
  targetType?: string;
  targetId?: string;
  from?: string;
  to?: string;
  page?: number;
  pageSize?: number;
}

/** ADMIN/SUPPORT server-side call — backs TICKET-012's Audit Log viewer. */
export async function listAuditLog(
  baseUrl: string,
  accessToken: string,
  filter: AuditLogFilter = {},
): Promise<AuditLogPage> {
  const params = new URLSearchParams();
  if (filter.actorUserId) params.set("actorUserId", filter.actorUserId);
  if (filter.targetType) params.set("targetType", filter.targetType);
  if (filter.targetId) params.set("targetId", filter.targetId);
  if (filter.from) params.set("from", filter.from);
  if (filter.to) params.set("to", filter.to);
  if (filter.page !== undefined) params.set("page", String(filter.page));
  if (filter.pageSize !== undefined) params.set("pageSize", String(filter.pageSize));
  const query = params.toString();
  return coreAppFetch<AuditLogPage>(baseUrl, `/api/v1/admin/audit-log${query ? `?${query}` : ""}`, {
    method: "GET",
    accessToken,
  });
}

// ==================== TICKET-110 — Broker Account Linking ====================

export async function listBrokerAccounts(
  baseUrl: string,
  accessToken: string,
): Promise<BrokerAccountSummary[]> {
  return coreAppFetch<BrokerAccountSummary[]>(baseUrl, "/api/v1/broker-accounts", {
    method: "GET",
    accessToken,
  });
}

export async function getBrokerAccount(
  baseUrl: string,
  accessToken: string,
  id: string,
): Promise<BrokerAccountSummary> {
  return coreAppFetch<BrokerAccountSummary>(baseUrl, `/api/v1/broker-accounts/${id}`, {
    method: "GET",
    accessToken,
  });
}

export async function patchBrokerAccount(
  baseUrl: string,
  accessToken: string,
  id: string,
  input: { displayLabel?: string; connectionRole?: ConnectionRole },
): Promise<BrokerAccountSummary> {
  return coreAppFetch<BrokerAccountSummary>(baseUrl, `/api/v1/broker-accounts/${id}`, {
    method: "PATCH",
    accessToken,
    body: JSON.stringify({
      ...(input.displayLabel !== undefined ? { display_label: input.displayLabel } : {}),
      ...(input.connectionRole !== undefined ? { connection_role: input.connectionRole } : {}),
    }),
  });
}

export async function deleteBrokerAccount(
  baseUrl: string,
  accessToken: string,
  id: string,
): Promise<void> {
  await coreAppFetch<null>(baseUrl, `/api/v1/broker-accounts/${id}`, {
    method: "DELETE",
    accessToken,
  });
}

/**
 * Required before {@link deleteBrokerAccount} — deleting a still-connected account is rejected
 * (`broker_account_not_disconnected`, 409). See BrokerAccountService#deleteBrokerAccount's own
 * Javadoc.
 */
export async function disconnectBrokerAccount(
  baseUrl: string,
  accessToken: string,
  id: string,
): Promise<BrokerAccountSummary> {
  return coreAppFetch<BrokerAccountSummary>(baseUrl, `/api/v1/broker-accounts/${id}/disconnect`, {
    method: "POST",
    accessToken,
  });
}

/**
 * TICKET-101 follow-up — the automatic fallback {@link deleteBrokerAccount} takes on
 * `broker_account_in_use` (409): rather than dead-ending, this archives every referencing row
 * (copy relationships, copied trades, trade signals, performance-fee-ledger entries, management
 * agreements) to a durable blob first, then deletes them and the account row itself. See
 * BrokerAccountArchivalOrchestrator's own Javadoc for the full ordering.
 */
export async function archiveAndDeleteBrokerAccount(
  baseUrl: string,
  accessToken: string,
  id: string,
): Promise<{ blobKey: string; rowCounts: Record<string, number> }> {
  return coreAppFetch(baseUrl, `/api/v1/broker-accounts/${id}/archive-and-delete`, {
    method: "POST",
    accessToken,
  });
}

export async function getBrokerAccountSnapshot(
  baseUrl: string,
  accessToken: string,
  id: string,
): Promise<BrokerAccountSnapshot> {
  return coreAppFetch<BrokerAccountSnapshot>(baseUrl, `/api/v1/broker-accounts/${id}/snapshot`, {
    method: "GET",
    accessToken,
  });
}

export async function getBrokerAccountPositions(
  baseUrl: string,
  accessToken: string,
  id: string,
): Promise<NormalizedPosition[]> {
  return coreAppFetch<NormalizedPosition[]>(baseUrl, `/api/v1/broker-accounts/${id}/positions`, {
    method: "GET",
    accessToken,
  });
}

export async function getCtraderAuthorizeUrl(
  baseUrl: string,
  accessToken: string,
): Promise<{ authorizeUrl: string }> {
  return coreAppFetch<{ authorizeUrl: string }>(baseUrl, "/api/v1/broker/ctrader/authorize-url", {
    method: "GET",
    accessToken,
  });
}

export interface CtraderCallbackResult {
  linkSessionId: string;
  accounts: CtraderAccountOption[];
}

/** No accessToken — cTrader's own redirect carries no bearer token, see core-app's own Javadoc. */
export async function submitCtraderCallback(
  baseUrl: string,
  code: string,
  state: string,
): Promise<CtraderCallbackResult> {
  return coreAppFetch<CtraderCallbackResult>(baseUrl, "/api/v1/broker/ctrader/callback", {
    method: "POST",
    body: JSON.stringify({ code, state }),
  });
}

export async function linkCtraderAccount(
  baseUrl: string,
  accessToken: string,
  input: {
    linkSessionId: string;
    ctidTraderAccountId: number;
    isLive: boolean;
    displayLabel: string;
    connectionRole?: ConnectionRole;
    openedViaIbLinkId?: string;
    brokerName?: string;
  },
): Promise<BrokerAccountSummary> {
  return coreAppFetch<BrokerAccountSummary>(baseUrl, "/api/v1/broker/ctrader/link", {
    method: "POST",
    accessToken,
    body: JSON.stringify({
      link_session_id: input.linkSessionId,
      ctid_trader_account_id: input.ctidTraderAccountId,
      is_live: input.isLive,
      display_label: input.displayLabel,
      connection_role: input.connectionRole,
      opened_via_ib_link_id: input.openedViaIbLinkId,
      broker_name: input.brokerName,
    }),
  });
}

export interface MtLinkResult {
  id: string;
  pairingToken: string;
  gatewayUrl: string;
  connectionStatus: ConnectionStatus;
}

export interface MtLinkInput {
  login: string;
  password: string;
  server: string;
  isDemo: boolean;
  displayLabel: string;
  connectionRole?: ConnectionRole;
  openedViaIbLinkId?: string;
  brokerName: string;
}

function mtLinkBody(input: MtLinkInput) {
  return JSON.stringify({
    login: input.login,
    password: input.password,
    server: input.server,
    is_demo: input.isDemo,
    display_label: input.displayLabel,
    connection_role: input.connectionRole,
    opened_via_ib_link_id: input.openedViaIbLinkId,
    broker_name: input.brokerName,
  });
}

export async function linkMt5Account(
  baseUrl: string,
  accessToken: string,
  input: MtLinkInput,
): Promise<MtLinkResult> {
  return coreAppFetch<MtLinkResult>(baseUrl, "/api/v1/broker-accounts/mt5", {
    method: "POST",
    accessToken,
    body: mtLinkBody(input),
  });
}

export async function linkMt4Account(
  baseUrl: string,
  accessToken: string,
  input: MtLinkInput,
): Promise<MtLinkResult> {
  return coreAppFetch<MtLinkResult>(baseUrl, "/api/v1/broker-accounts/mt4", {
    method: "POST",
    accessToken,
    body: mtLinkBody(input),
  });
}

export async function listSymbolMappings(
  baseUrl: string,
  accessToken: string,
  brokerAccountId: string,
): Promise<SymbolMappingEntry[]> {
  return coreAppFetch<SymbolMappingEntry[]>(
    baseUrl,
    `/api/v1/broker-accounts/${brokerAccountId}/symbol-mappings`,
    { method: "GET", accessToken },
  );
}

export async function confirmSymbolMapping(
  baseUrl: string,
  accessToken: string,
  brokerAccountId: string,
  canonicalSymbol: string,
  brokerSymbolName: string,
): Promise<SymbolMappingEntry> {
  return coreAppFetch<SymbolMappingEntry>(
    baseUrl,
    `/api/v1/broker-accounts/${brokerAccountId}/symbol-mappings/${canonicalSymbol}`,
    { method: "PUT", accessToken, body: JSON.stringify({ broker_symbol_name: brokerSymbolName }) },
  );
}

/**
 * TICKET-116 — the manual fallback for a canonical symbol {@link confirmSymbolMapping} can't reach
 * (no auto-suggested row exists yet). {@code brokerSymbolName} is verified against a live broker
 * round trip server-side before anything is written — a 422 means the broker didn't recognize it.
 */
export async function createOrConfirmSymbolMapping(
  baseUrl: string,
  accessToken: string,
  brokerAccountId: string,
  canonicalSymbol: string,
  brokerSymbolName: string,
): Promise<SymbolMappingEntry> {
  return coreAppFetch<SymbolMappingEntry>(
    baseUrl,
    `/api/v1/broker-accounts/${brokerAccountId}/symbol-mappings/${canonicalSymbol}/resolve`,
    { method: "POST", accessToken, body: JSON.stringify({ broker_symbol_name: brokerSymbolName }) },
  );
}

/**
 * Returns [] gracefully when a Master has no active IB links. TICKET-110's own narrow,
 * additive read (any authenticated caller, e.g. a Follower during onboarding, may look up a
 * specific Master's active links by id — see BrokerIbLinkController's own Javadoc for why that's
 * safe) — for the calling Master's own full management view, see {@link listMyBrokerIbLinks}.
 */
export async function listMasterIbLinks(
  baseUrl: string,
  accessToken: string,
  masterProfileId: string,
): Promise<BrokerIbLink[]> {
  return coreAppFetch<BrokerIbLink[]>(
    baseUrl,
    `/api/v1/broker-accounts/ib-links?masterProfileId=${masterProfileId}`,
    { method: "GET", accessToken },
  );
}

// ==================== TICKET-119 — Broker IB Link Management (Master-scoped) ====================

export async function createBrokerIbLink(
  baseUrl: string,
  accessToken: string,
  input: { brokerType: string; brokerDisplayName: string; ibReferralUrlOrCode: string },
): Promise<BrokerIbLink> {
  return coreAppFetch<BrokerIbLink>(baseUrl, "/api/v1/master/broker-ib-links", {
    method: "POST",
    accessToken,
    body: JSON.stringify({
      broker_type: input.brokerType,
      broker_display_name: input.brokerDisplayName,
      ib_referral_url_or_code: input.ibReferralUrlOrCode,
    }),
  });
}

/** Every link the calling Master has ever created, active or not — see BrokerIbLinkService's own Javadoc. */
export async function listMyBrokerIbLinks(
  baseUrl: string,
  accessToken: string,
): Promise<BrokerIbLink[]> {
  return coreAppFetch<BrokerIbLink[]>(baseUrl, "/api/v1/master/broker-ib-links", {
    method: "GET",
    accessToken,
  });
}

/** Deliberately never a hard delete — see BrokerIbLinkRepository#deactivate's own Javadoc. */
export async function deactivateBrokerIbLink(
  baseUrl: string,
  accessToken: string,
  id: string,
): Promise<void> {
  await coreAppFetch<null>(baseUrl, `/api/v1/master/broker-ib-links/${id}/deactivate`, {
    method: "POST",
    accessToken,
  });
}

// ==================== TICKET-111 — Master Profile Creation & CopyRelationship State Machine ====================

export interface CreateMasterProfileInput {
  brokerAccountId: string;
  displayName: string;
  bio?: string;
  strategyTags?: string[];
  performanceFeePercent?: number;
  feeCollectionMethod?: FeeCollectionMethod;
}

/** Throws ApiError(409) with body.existing_profile_id if the caller already has one — see MasterProfileExceptionHandler. */
export async function createMasterProfile(
  baseUrl: string,
  accessToken: string,
  input: CreateMasterProfileInput,
): Promise<MasterProfile> {
  return coreAppFetch<MasterProfile>(baseUrl, "/api/v1/master-profiles", {
    method: "POST",
    accessToken,
    body: JSON.stringify({
      broker_account_id: input.brokerAccountId,
      display_name: input.displayName,
      bio: input.bio,
      strategy_tags: input.strategyTags,
      performance_fee_percent: input.performanceFeePercent,
      fee_collection_method: input.feeCollectionMethod,
    }),
  });
}

export async function getMasterProfile(
  baseUrl: string,
  accessToken: string,
  id: string,
): Promise<MasterProfile> {
  return coreAppFetch<MasterProfile>(baseUrl, `/api/v1/master-profiles/${id}`, {
    method: "GET",
    accessToken,
  });
}

/** TICKET-116 — the caller's own profile, by JWT subject (was only 409-discoverable before). */
export async function getMyMasterProfile(
  baseUrl: string,
  accessToken: string,
): Promise<MasterProfile> {
  return coreAppFetch<MasterProfile>(baseUrl, "/api/v1/master-profiles/me", {
    method: "GET",
    accessToken,
  });
}

export async function patchMasterProfile(
  baseUrl: string,
  accessToken: string,
  id: string,
  input: {
    displayName?: string;
    bio?: string;
    strategyTags?: string[];
    performanceFeePercent?: number;
    isPublic?: boolean;
  },
): Promise<MasterProfile> {
  return coreAppFetch<MasterProfile>(baseUrl, `/api/v1/master-profiles/${id}`, {
    method: "PATCH",
    accessToken,
    body: JSON.stringify({
      display_name: input.displayName,
      bio: input.bio,
      strategy_tags: input.strategyTags,
      performance_fee_percent: input.performanceFeePercent,
      is_public: input.isPublic,
    }),
  });
}

/**
 * Bugfix — lets a Master change which of their own broker accounts is their primary one, cascading
 * to any existing non-terminal copy_relationships rows still pointing at the old one (see
 * bootstrap.archival.MasterPrimaryBrokerAccountOrchestrator's own Javadoc). This is what was
 * missing that caused a Master's trades to stop being copied after linking a new broker account.
 */
export async function changeMasterPrimaryBrokerAccount(
  baseUrl: string,
  accessToken: string,
  masterProfileId: string,
  brokerAccountId: string,
): Promise<PrimaryBrokerAccountChange> {
  return coreAppFetch<PrimaryBrokerAccountChange>(
    baseUrl,
    `/api/v1/master-profiles/${masterProfileId}/primary-broker-account`,
    {
      method: "PATCH",
      accessToken,
      body: JSON.stringify({ broker_account_id: brokerAccountId }),
    },
  );
}

export async function listCopyRelationships(
  baseUrl: string,
  accessToken: string,
  filter: { role?: "follower" | "master"; status?: CopyRelationshipStatus } = {},
): Promise<CopyRelationship[]> {
  const params = new URLSearchParams();
  if (filter.role) params.set("role", filter.role);
  if (filter.status) params.set("status", filter.status);
  const query = params.toString();
  return coreAppFetch<CopyRelationship[]>(
    baseUrl,
    `/api/v1/copy-relationships${query ? `?${query}` : ""}`,
    { method: "GET", accessToken },
  );
}

export async function getCopyRelationship(
  baseUrl: string,
  accessToken: string,
  id: string,
): Promise<CopyRelationship> {
  return coreAppFetch<CopyRelationship>(baseUrl, `/api/v1/copy-relationships/${id}`, {
    method: "GET",
    accessToken,
  });
}

export interface CopySettingsInput {
  method: string;
  fixedLotSize: number | null;
  multiplier: number | null;
  riskPercent: number | null;
  roundingMode: string;
  maxLotPerTrade: number | null;
  maxOpenPositions: number | null;
  maxSlippagePips: number | null;
  excludedSymbols: string[];
}

/**
 * TICKET-116 — edits the relationship's money-management/risk profile rows in place, distinct from
 * {@link patchCopyRelationship}'s "swap to a different existing profile id" shape. Always a
 * full-object submit (mirrors the form it backs, pre-filled with the relationship's current values).
 */
export async function updateCopySettings(
  baseUrl: string,
  accessToken: string,
  id: string,
  input: CopySettingsInput,
): Promise<CopyRelationship> {
  return coreAppFetch<CopyRelationship>(baseUrl, `/api/v1/copy-relationships/${id}/copy-settings`, {
    method: "PATCH",
    accessToken,
    body: JSON.stringify({
      method: input.method,
      fixed_lot_size: input.fixedLotSize,
      multiplier: input.multiplier,
      risk_percent: input.riskPercent,
      rounding_mode: input.roundingMode,
      max_lot_per_trade: input.maxLotPerTrade,
      max_open_positions: input.maxOpenPositions,
      max_slippage_pips: input.maxSlippagePips,
      excluded_symbols: input.excludedSymbols,
    }),
  });
}

/**
 * allocationWeight is accepted by core-app but has no backing column yet
 * (Phase-2/portfolio-module territory) — included here for contract parity but silently a no-op.
 */
export async function patchCopyRelationship(
  baseUrl: string,
  accessToken: string,
  id: string,
  input: { moneyManagementProfileId?: string; riskProfileId?: string; allocationWeight?: number },
): Promise<CopyRelationship> {
  return coreAppFetch<CopyRelationship>(baseUrl, `/api/v1/copy-relationships/${id}`, {
    method: "PATCH",
    accessToken,
    body: JSON.stringify({
      money_management_profile_id: input.moneyManagementProfileId,
      risk_profile_id: input.riskProfileId,
      allocation_weight: input.allocationWeight,
    }),
  });
}

function copyRelationshipAction(
  baseUrl: string,
  accessToken: string,
  id: string,
  action: "acknowledge-risk" | "sign-agreement" | "pause" | "resume" | "stop",
): Promise<CopyRelationship> {
  return coreAppFetch<CopyRelationship>(baseUrl, `/api/v1/copy-relationships/${id}/${action}`, {
    method: "POST",
    accessToken,
  });
}

/** AC2 — the risk-acknowledgement gate. Server-side enforced; this call is what actually flips state, not a UI-only step. */
export async function acknowledgeRisk(
  baseUrl: string,
  accessToken: string,
  id: string,
): Promise<CopyRelationship> {
  return copyRelationshipAction(baseUrl, accessToken, id, "acknowledge-risk");
}

/** AC3 — only has effect from PENDING_AGREEMENT; core-app rejects otherwise. */
export async function signAgreement(
  baseUrl: string,
  accessToken: string,
  id: string,
): Promise<CopyRelationship> {
  return copyRelationshipAction(baseUrl, accessToken, id, "sign-agreement");
}

/**
 * TICKET-120 AC2 — {@code documentUrl} is a short-lived signed URL, never a public one; throws
 * ApiError(404) if this relationship hasn't been signed yet (see
 * ManagementAgreementNotFoundException).
 */
export async function getManagementAgreement(
  baseUrl: string,
  accessToken: string,
  id: string,
): Promise<{ id: string; status: string; signedAt: string; documentUrl: string }> {
  return coreAppFetch(baseUrl, `/api/v1/copy-relationships/${id}/agreement`, {
    method: "GET",
    accessToken,
  });
}

export async function pauseCopyRelationship(
  baseUrl: string,
  accessToken: string,
  id: string,
): Promise<CopyRelationship> {
  return copyRelationshipAction(baseUrl, accessToken, id, "pause");
}

export async function resumeCopyRelationship(
  baseUrl: string,
  accessToken: string,
  id: string,
): Promise<CopyRelationship> {
  return copyRelationshipAction(baseUrl, accessToken, id, "resume");
}

/** AC4 — force-closes all open positions asynchronously via copy-engine's stop-closure poller, not synchronously in this call. */
export async function stopCopyRelationship(
  baseUrl: string,
  accessToken: string,
  id: string,
): Promise<CopyRelationship> {
  return copyRelationshipAction(baseUrl, accessToken, id, "stop");
}

export async function listCopyRelationshipTrades(
  baseUrl: string,
  accessToken: string,
  id: string,
  page = 0,
  pageSize = 20,
): Promise<CopiedTradesPage> {
  return coreAppFetch<CopiedTradesPage>(
    baseUrl,
    `/api/v1/copy-relationships/${id}/trades?page=${page}&pageSize=${pageSize}`,
    { method: "GET", accessToken },
  );
}

/**
 * TICKET-116 — cross-relationship trade history (every relationship the caller has, not just one),
 * backing both the dashboard's "recent activity" and the dedicated trade-history page.
 */
export async function listAllCopiedTrades(
  baseUrl: string,
  accessToken: string,
  filter: {
    role?: "follower" | "master";
    symbol?: string;
    from?: string;
    to?: string;
    status?: CopiedTradeStatus;
    relationshipId?: string;
    page?: number;
    pageSize?: number;
  } = {},
): Promise<CopiedTradesPage> {
  const params = new URLSearchParams();
  if (filter.role) params.set("role", filter.role);
  if (filter.symbol) params.set("symbol", filter.symbol);
  if (filter.from) params.set("from", filter.from);
  if (filter.to) params.set("to", filter.to);
  if (filter.status) params.set("status", filter.status);
  if (filter.relationshipId) params.set("relationshipId", filter.relationshipId);
  if (filter.page !== undefined) params.set("page", String(filter.page));
  if (filter.pageSize !== undefined) params.set("pageSize", String(filter.pageSize));
  const query = params.toString();
  return coreAppFetch<CopiedTradesPage>(
    baseUrl,
    `/api/v1/copy-relationships/trades${query ? `?${query}` : ""}`,
    { method: "GET", accessToken },
  );
}

// ==================== TICKET-116 — Master Analytics ====================

export type AnalyticsPeriod = "7D" | "30D" | "90D" | "YTD" | "ALL";

export interface DailyEquityPoint {
  day: string;
  equity: number;
}

export interface MonthlyReturn {
  month: string;
  returnPct: number;
}

export interface InstrumentPnl {
  canonicalSymbol: string;
  totalPnl: number;
  tradeCount: number;
}

export interface MasterAnalytics {
  equityCurve: DailyEquityPoint[];
  monthlyReturns: MonthlyReturn[];
  pnlByInstrument: InstrumentPnl[];
}

export async function getMasterAnalytics(
  baseUrl: string,
  accessToken: string,
  masterProfileId: string,
  period: AnalyticsPeriod = "30D",
): Promise<MasterAnalytics> {
  return coreAppFetch<MasterAnalytics>(
    baseUrl,
    `/api/v1/master-profiles/${masterProfileId}/analytics?period=${period}`,
    { method: "GET", accessToken },
  );
}

// ==================== Feature — Follower Analytics ====================
// Same response shape as MasterAnalytics (equityCurve/monthlyReturns/pnlByInstrument) — see
// FollowerAnalyticsService's own Javadoc for why it's an aggregation across every broker account
// this Follower has ever copied onto, not a single-account lookup.
export type FollowerAnalytics = MasterAnalytics;

export async function getFollowerAnalytics(
  baseUrl: string,
  accessToken: string,
  period: AnalyticsPeriod = "30D",
): Promise<FollowerAnalytics> {
  return coreAppFetch<FollowerAnalytics>(
    baseUrl,
    `/api/v1/followers/me/analytics?period=${period}`,
    { method: "GET", accessToken },
  );
}

// ==================== TICKET-112 — Public Discovery (Leaderboard) ====================
// Both functions below deliberately take no accessToken — docs/14-api-specification.md §14.4:
// "Discovery endpoints remain public/unauthenticated" (same no-token precedent as
// submitCtraderCallback's own broker-redirect call above).

export async function listLeaderboard(
  baseUrl: string,
  filter: { period?: LeaderboardPeriod; sort?: LeaderboardSort; page?: number } = {},
): Promise<LeaderboardEntry[]> {
  const params = new URLSearchParams();
  if (filter.period) params.set("period", filter.period);
  if (filter.sort) params.set("sort", filter.sort);
  if (filter.page !== undefined) params.set("page", String(filter.page));
  const query = params.toString();
  return coreAppFetch<LeaderboardEntry[]>(
    baseUrl,
    `/api/v1/discovery/leaderboard${query ? `?${query}` : ""}`,
    { method: "GET" },
  );
}

export async function getPublicMasterProfile(
  baseUrl: string,
  id: string,
): Promise<PublicMasterProfile> {
  return coreAppFetch<PublicMasterProfile>(baseUrl, `/api/v1/discovery/masters/${id}`, {
    method: "GET",
  });
}

// ==================== TICKET-114 — Self-Serve Registration & Subscriptions ====================
// registerUser takes no accessToken — public, no-invitation-required registration (the one
// deliberate exception to "no self-registration anywhere", see core-app's UserProvisioningApi).

export interface RegisteredUser {
  userId: string;
}

export async function registerUser(
  baseUrl: string,
  input: { email: string; password: string; displayName: string },
): Promise<RegisteredUser> {
  return coreAppFetch<RegisteredUser>(baseUrl, "/api/v1/auth/register", {
    method: "POST",
    body: JSON.stringify({
      email: input.email,
      password: input.password,
      display_name: input.displayName,
    }),
  });
}

export type SubscriptionPlanCode = "STARTER" | "INDIVIDUAL" | "PRO";

// ==================== TICKET-115 — Notification Preferences ====================

export type NotificationChannel = "IN_APP" | "PUSH" | "EMAIL" | "SMS";

export interface NotificationPreference {
  userId: string;
  eventType: string;
  channel: NotificationChannel;
  enabled: boolean;
}

export async function getNotificationPreferences(
  baseUrl: string,
  accessToken: string,
): Promise<NotificationPreference[]> {
  return coreAppFetch<NotificationPreference[]>(baseUrl, "/api/v1/notification-preferences", {
    method: "GET",
    accessToken,
  });
}

/** Throws ApiError(400) with body.error === "drawdown_notification_floor_violation" for the one
 * event type that can never have its IN_APP channel disabled — see core-app's
 * NotificationPreferenceService for the full rule. */
export async function updateNotificationPreference(
  baseUrl: string,
  accessToken: string,
  input: { eventType: string; channel: NotificationChannel; enabled: boolean },
): Promise<void> {
  await coreAppFetch<null>(baseUrl, "/api/v1/notification-preferences", {
    method: "PUT",
    accessToken,
    body: JSON.stringify({
      event_type: input.eventType,
      channel: input.channel,
      enabled: input.enabled,
    }),
  });
}

// ==================== TICKET-116 — Notification Center ====================
// The IN_APP notification_log rows themselves — distinct from the preferences above. `payload` is
// a JSON-encoded string (core-app's NotificationDispatchService serializes {event_type,title,body}
// into it) — callers parse it themselves via parseNotificationPayload below, this client doesn't
// assume a shape core-app is free to evolve.

export interface NotificationLogItem {
  id: string;
  eventType: string;
  payload: string;
  createdAt: string;
  readAt: string | null;
}

export interface NotificationPayload {
  eventType: string;
  title: string;
  body: string;
}

export function parseNotificationPayload(item: NotificationLogItem): NotificationPayload {
  try {
    return JSON.parse(item.payload) as NotificationPayload;
  } catch {
    return { eventType: item.eventType, title: item.eventType, body: "" };
  }
}

export async function listNotifications(
  baseUrl: string,
  accessToken: string,
  unreadOnly = false,
): Promise<NotificationLogItem[]> {
  return coreAppFetch<NotificationLogItem[]>(
    baseUrl,
    `/api/v1/notifications${unreadOnly ? "?unread=true" : ""}`,
    { method: "GET", accessToken },
  );
}

export async function markNotificationRead(
  baseUrl: string,
  accessToken: string,
  id: string,
): Promise<void> {
  await coreAppFetch<null>(baseUrl, `/api/v1/notifications/${id}/read`, {
    method: "POST",
    accessToken,
  });
}

/** Every plan (including the entry tier) requires a card on file — always returns a Checkout URL. */
export async function startSubscriptionCheckout(
  baseUrl: string,
  accessToken: string,
  planCode: SubscriptionPlanCode,
): Promise<{ checkoutUrl: string }> {
  return coreAppFetch<{ checkoutUrl: string }>(baseUrl, "/api/v1/subscriptions", {
    method: "POST",
    accessToken,
    body: JSON.stringify({ plan_code: planCode }),
  });
}

// TICKET-117 — Admin MVP (System Health + User Management + Disputes). ADMIN/SUPPORT
// server-side calls, same convention every other admin-portal-facing function above uses.

/**
 * A blank/absent `search` returns every user (newest first) — see UserRepository#search's own
 * Javadoc. `status` is the Users page's own status filter beside the search box
 * (ACTIVE/SUSPENDED/DELETED) — blank/absent means the default view (every status except DELETED).
 */
export async function searchUsers(
  baseUrl: string,
  accessToken: string,
  search = "",
  status = "",
  page = 0,
  pageSize = 25,
): Promise<UserSummary[]> {
  const params = new URLSearchParams({
    search,
    status,
    page: String(page),
    pageSize: String(pageSize),
  });
  return coreAppFetch<UserSummary[]>(baseUrl, `/api/v1/admin/users?${params.toString()}`, {
    method: "GET",
    accessToken,
  });
}

export async function getUserDetail(
  baseUrl: string,
  accessToken: string,
  id: string,
): Promise<UserDetail> {
  return coreAppFetch<UserDetail>(baseUrl, `/api/v1/admin/users/${id}`, {
    method: "GET",
    accessToken,
  });
}

/** ADMIN-only server-side — SecurityConfig/AdminController both enforce this, not just the UI. */
export async function suspendUser(
  baseUrl: string,
  accessToken: string,
  id: string,
): Promise<UserSummary> {
  return coreAppFetch<UserSummary>(baseUrl, `/api/v1/admin/users/${id}/suspend`, {
    method: "PATCH",
    accessToken,
  });
}

export async function reinstateUser(
  baseUrl: string,
  accessToken: string,
  id: string,
): Promise<UserSummary> {
  return coreAppFetch<UserSummary>(baseUrl, `/api/v1/admin/users/${id}/reinstate`, {
    method: "PATCH",
    accessToken,
  });
}

/** ADMIN-only server-side — sets users.status = 'DELETED', same enforcement path as suspend. */
export async function deleteUser(
  baseUrl: string,
  accessToken: string,
  id: string,
): Promise<UserSummary> {
  return coreAppFetch<UserSummary>(baseUrl, `/api/v1/admin/users/${id}`, {
    method: "DELETE",
    accessToken,
  });
}

/**
 * #421 — ADMIN/SUPER_ADMIN-only server-side call: links an existing Follower directly to an
 * existing Master (identified by email), bypassing the invite-send/invite-accept and
 * follow-request flows. See AdminController#linkFollowerToMaster's own Javadoc for the
 * PENDING_AGREEMENT-vs-ACTIVE status derivation.
 */
export async function linkFollowerToMaster(
  baseUrl: string,
  accessToken: string,
  followerId: string,
  input: { masterEmail: string; followerBrokerAccountId: string },
): Promise<LinkFollowerToMasterResult> {
  return coreAppFetch<LinkFollowerToMasterResult>(
    baseUrl,
    `/api/v1/admin/users/${followerId}/copy-relationships`,
    {
      method: "POST",
      accessToken,
      body: JSON.stringify({
        master_email: input.masterEmail,
        follower_broker_account_id: input.followerBrokerAccountId,
      }),
    },
  );
}

/** `status` defaults to DISPUTED server-side — see the ticket's own scope note. */
export async function listDisputedLedgerEntries(
  baseUrl: string,
  accessToken: string,
  status: FeeLedgerStatus = "DISPUTED",
  page = 0,
  pageSize = 25,
): Promise<FeeLedgerEntry[]> {
  const params = new URLSearchParams({ status, page: String(page), pageSize: String(pageSize) });
  return coreAppFetch<FeeLedgerEntry[]>(baseUrl, `/api/v1/admin/fee-ledger?${params.toString()}`, {
    method: "GET",
    accessToken,
  });
}

export async function getFeeLedgerDetail(
  baseUrl: string,
  accessToken: string,
  id: string,
): Promise<FeeLedgerDetailPage> {
  return coreAppFetch<FeeLedgerDetailPage>(baseUrl, `/api/v1/admin/fee-ledger/${id}`, {
    method: "GET",
    accessToken,
  });
}

/** ADMIN+SUPPORT — the only real way performance_fee_ledger.status can ever become DISPUTED. */
export async function raiseDispute(
  baseUrl: string,
  accessToken: string,
  id: string,
  reason: string,
): Promise<void> {
  await coreAppFetch<null>(baseUrl, `/api/v1/admin/fee-ledger/${id}/dispute`, {
    method: "POST",
    accessToken,
    body: JSON.stringify({ reason }),
  });
}

/** ADMIN-only server-side — matches the ticket's own RBAC line (financial-ledger action). */
export async function resolveDispute(
  baseUrl: string,
  accessToken: string,
  id: string,
  input: { resolution: FeeLedgerResolution; note: string; adjustedAmount?: number },
): Promise<void> {
  await coreAppFetch<null>(baseUrl, `/api/v1/admin/fee-ledger/${id}/resolve`, {
    method: "POST",
    accessToken,
    body: JSON.stringify({
      resolution: input.resolution,
      note: input.note,
      adjusted_amount: input.adjustedAmount ?? null,
    }),
  });
}

/**
 * Built from Postgres + a real Kafka AdminClient, not Prometheus — see AdminController's own
 * Javadoc for why (no Prometheus anywhere outside local dev/CI, including nectrix-dev).
 */
export async function getSystemHealth(
  baseUrl: string,
  accessToken: string,
): Promise<SystemHealthSnapshot> {
  return coreAppFetch<SystemHealthSnapshot>(baseUrl, "/api/v1/admin/system-health", {
    method: "GET",
    accessToken,
  });
}

// TICKET-117 follow-up — a real Master or Follower's own settlement/invoice history + self-
// service dispute-raising (apps/web). Ownership-scoped server-side (FeeLedgerService) — distinct
// from the admin/staff-only functions above, which see every caller's rows.

export async function listMySettlements(
  baseUrl: string,
  accessToken: string,
  page = 0,
  pageSize = 25,
): Promise<FeeLedgerEntry[]> {
  const params = new URLSearchParams({ page: String(page), pageSize: String(pageSize) });
  return coreAppFetch<FeeLedgerEntry[]>(baseUrl, `/api/v1/fee-ledger?${params.toString()}`, {
    method: "GET",
    accessToken,
  });
}

export async function getMySettlementDetail(
  baseUrl: string,
  accessToken: string,
  id: string,
): Promise<FeeLedgerDetailPage> {
  return coreAppFetch<FeeLedgerDetailPage>(baseUrl, `/api/v1/fee-ledger/${id}`, {
    method: "GET",
    accessToken,
  });
}

/** Either party (Master or Follower) — 409s if the row is already DISPUTED or VOID. */
export async function raiseMySettlementDispute(
  baseUrl: string,
  accessToken: string,
  id: string,
): Promise<void> {
  await coreAppFetch<null>(baseUrl, `/api/v1/fee-ledger/${id}/dispute`, {
    method: "POST",
    accessToken,
  });
}

// ==================== TICKET-120 — Broker Fee Reports (Master-scoped) ====================

/** Throws ApiError(400, "no_pending_fees_to_report") if there's nothing to bundle for this (broker, period). */
export async function generateBrokerFeeReport(
  baseUrl: string,
  accessToken: string,
  input: { brokerType: string; periodStart: string; periodEnd: string },
): Promise<BrokerFeeReport> {
  return coreAppFetch<BrokerFeeReport>(baseUrl, "/api/v1/master/fee-reports", {
    method: "POST",
    accessToken,
    body: JSON.stringify({
      broker_type: input.brokerType,
      period_start: input.periodStart,
      period_end: input.periodEnd,
    }),
  });
}

export async function listMyBrokerFeeReports(
  baseUrl: string,
  accessToken: string,
): Promise<BrokerFeeReport[]> {
  return coreAppFetch<BrokerFeeReport[]>(baseUrl, "/api/v1/master/fee-reports", {
    method: "GET",
    accessToken,
  });
}

export async function getMyBrokerFeeReport(
  baseUrl: string,
  accessToken: string,
  id: string,
): Promise<BrokerFeeReportDetail> {
  return coreAppFetch<BrokerFeeReportDetail>(baseUrl, `/api/v1/master/fee-reports/${id}`, {
    method: "GET",
    accessToken,
  });
}

async function feeReportAction(
  baseUrl: string,
  accessToken: string,
  id: string,
  action: string,
): Promise<BrokerFeeReport> {
  return coreAppFetch<BrokerFeeReport>(baseUrl, `/api/v1/master/fee-reports/${id}/${action}`, {
    method: "POST",
    accessToken,
  });
}

/** Only valid from DRAFT — core-app rejects otherwise. */
export async function sendBrokerFeeReport(
  baseUrl: string,
  accessToken: string,
  id: string,
): Promise<BrokerFeeReport> {
  return feeReportAction(baseUrl, accessToken, id, "send");
}

/** Only valid from SENT — cascades every bundled performance_fee_ledger row to BROKER_CONFIRMED_DEDUCTED. */
export async function confirmBrokerFeeReportDeducted(
  baseUrl: string,
  accessToken: string,
  id: string,
): Promise<BrokerFeeReport> {
  return feeReportAction(baseUrl, accessToken, id, "confirm-deducted");
}

/** Only valid from BROKER_CONFIRMED_DEDUCTED — cascades every bundled ledger row to BROKER_CONFIRMED_PAID. */
export async function confirmBrokerFeeReportPaid(
  baseUrl: string,
  accessToken: string,
  id: string,
): Promise<BrokerFeeReport> {
  return feeReportAction(baseUrl, accessToken, id, "confirm-paid");
}

// ==================== TICKET-122 — Account Tier-Change Requests ====================

/** Throws ApiError(409, "already_master_or_follower" | "pending_request_exists") or ApiError(400, "agreement_not_accepted"). */
export async function submitTierChangeRequest(
  baseUrl: string,
  accessToken: string,
  input: { targetMode: TierChangeTargetRole; agreementAccepted: boolean },
): Promise<TierChangeRequest> {
  return coreAppFetch<TierChangeRequest>(baseUrl, "/api/v1/account/tier-change-requests", {
    method: "POST",
    accessToken,
    body: JSON.stringify({
      target_mode: input.targetMode,
      agreement_accepted: input.agreementAccepted,
    }),
  });
}

/**
 * The caller's own most recent request, whatever its status — null if they've never submitted one
 * (the controller returns 204 No Content for that case; coreAppFetch's own empty-body handling
 * already turns that into a plain `null`, no special-casing needed here).
 */
export async function getMyTierChangeRequest(
  baseUrl: string,
  accessToken: string,
): Promise<TierChangeRequest | null> {
  return coreAppFetch<TierChangeRequest | null>(baseUrl, "/api/v1/account/tier-change-requests/me", {
    method: "GET",
    accessToken,
  });
}

/** Admin Portal — the pending-review queue by default; pass status for APPROVED/REJECTED history. */
export async function listTierChangeRequests(
  baseUrl: string,
  accessToken: string,
  status: TierChangeRequestStatus = "PENDING",
): Promise<TierChangeRequest[]> {
  return coreAppFetch<TierChangeRequest[]>(
    baseUrl,
    `/api/v1/admin/tier-change-requests?status=${status}`,
    { method: "GET", accessToken },
  );
}

export async function getTierChangeRequest(
  baseUrl: string,
  accessToken: string,
  id: string,
): Promise<TierChangeRequest> {
  return coreAppFetch<TierChangeRequest>(baseUrl, `/api/v1/admin/tier-change-requests/${id}`, {
    method: "GET",
    accessToken,
  });
}

async function tierChangeRequestDecision(
  baseUrl: string,
  accessToken: string,
  id: string,
  decision: "approve" | "reject",
  reason: string | undefined,
): Promise<TierChangeRequest> {
  return coreAppFetch<TierChangeRequest>(
    baseUrl,
    `/api/v1/admin/tier-change-requests/${id}/${decision}`,
    { method: "POST", accessToken, body: JSON.stringify({ reason: reason ?? null }) },
  );
}

/** ADMIN/SUPER_ADMIN only — grants the requested role and notifies the user. */
export async function approveTierChangeRequest(
  baseUrl: string,
  accessToken: string,
  id: string,
  reason?: string,
): Promise<TierChangeRequest> {
  return tierChangeRequestDecision(baseUrl, accessToken, id, "approve", reason);
}

/** ADMIN/SUPER_ADMIN only — leaves the user's roles unchanged and notifies them (with reason, if given). */
export async function rejectTierChangeRequest(
  baseUrl: string,
  accessToken: string,
  id: string,
  reason?: string,
): Promise<TierChangeRequest> {
  return tierChangeRequestDecision(baseUrl, accessToken, id, "reject", reason);
}

// ==================== TICKET-118 — Invitation System (Master invites a Follower) ====================

/** MASTER-only server-side call — scoped to the caller's own master_profile_id (InvitationController). */
export async function createInvitation(
  baseUrl: string,
  accessToken: string,
  input: {
    invitedEmail: string;
    suggestedBrokerIbLinkId?: string;
    suggestedMoneyManagementProfileId?: string;
    suggestedRiskProfileId?: string;
  },
): Promise<Invitation> {
  return coreAppFetch<Invitation>(baseUrl, "/api/v1/master/invitations", {
    method: "POST",
    accessToken,
    body: JSON.stringify({
      invited_email: input.invitedEmail,
      suggested_broker_ib_link_id: input.suggestedBrokerIbLinkId ?? null,
      suggested_money_management_profile_id: input.suggestedMoneyManagementProfileId ?? null,
      suggested_risk_profile_id: input.suggestedRiskProfileId ?? null,
    }),
  });
}

export async function listMyInvitations(
  baseUrl: string,
  accessToken: string,
  status?: string,
): Promise<Invitation[]> {
  const query = status ? `?status=${status}` : "";
  return coreAppFetch<Invitation[]>(baseUrl, `/api/v1/master/invitations${query}`, {
    method: "GET",
    accessToken,
  });
}

/** No-ops safely (204) if the invitation is already non-PENDING — see InvitationService's own Javadoc. */
export async function revokeInvitation(
  baseUrl: string,
  accessToken: string,
  id: string,
): Promise<void> {
  await coreAppFetch<null>(baseUrl, `/api/v1/master/invitations/${id}/revoke`, {
    method: "POST",
    accessToken,
  });
}

/**
 * Resending isn't a one-shot affair — rotates the token/expiry and re-sends the email. Rate
 * limited per-invitation (429) and 409s for ACCEPTED/REVOKED invitations — see
 * InvitationService#resend's own Javadoc.
 */
export async function resendInvitation(
  baseUrl: string,
  accessToken: string,
  id: string,
): Promise<Invitation> {
  return coreAppFetch<Invitation>(baseUrl, `/api/v1/master/invitations/${id}/resend`, {
    method: "POST",
    accessToken,
  });
}

/**
 * Public, unauthenticated — the accept screen's own "who invited me?" preview. A 400
 * ({@code invalid_or_expired_invitation}) covers not-found/expired/revoked/already-accepted alike
 * (deliberately, never leaking which case applies).
 */
export async function getInvitationByToken(baseUrl: string, token: string): Promise<InvitationPreview> {
  return coreAppFetch<InvitationPreview>(baseUrl, `/api/v1/invitations/by-token/${token}`, {
    method: "GET",
  });
}

/**
 * Public, unauthenticated — creates the User (if none exists for the invited email) or reuses the
 * existing one, then issues a real session exactly like {@link login} does (see
 * AcceptInviteController's own Javadoc). Reuses {@link LoginResult}'s shape — same
 * accessToken/refreshToken/expiresIn fields core-app's TokenPairView returns.
 */
export async function acceptInvite(
  baseUrl: string,
  input: { token: string; password: string; displayName?: string },
): Promise<LoginResult> {
  return coreAppFetch<LoginResult>(baseUrl, "/api/v1/auth/accept-invite", {
    method: "POST",
    body: JSON.stringify({
      token: input.token,
      password: input.password,
      ...(input.displayName ? { display_name: input.displayName } : {}),
    }),
  });
}

/**
 * The caller's own not-yet-actioned invitation (from `users.created_via_invitation_id`), if any —
 * `null` on a 204 (no pending invitation, or already actioned). Only reliably covers the invitation
 * that created the caller's very first account; see InvitationCopySetupService's own Javadoc for
 * the multi-master case, which the frontend instead threads through explicitly.
 */
export async function getPendingInvitation(
  baseUrl: string,
  accessToken: string,
): Promise<PendingInvitation | null> {
  return coreAppFetch<PendingInvitation | null>(baseUrl, "/api/v1/users/me/pending-invitation", {
    method: "GET",
    accessToken,
  });
}

/**
 * The invitation-acceptance flow's own copy-relationship creation step, after broker linking.
 * Every field past `invitationId`/`followerBrokerAccountId` is optional — omitted ones default to
 * the invitation's own suggested profile's values server-side (same request shape as
 * {@link updateCopySettings}'s CopySettingsRequest, deliberately reused).
 */
export async function createCopyRelationshipFromInvitation(
  baseUrl: string,
  accessToken: string,
  input: {
    invitationId: string;
    followerBrokerAccountId: string;
    method?: string;
    fixedLotSize?: number;
    multiplier?: number;
    riskPercent?: number;
    roundingMode?: string;
    maxLotPerTrade?: number;
    maxOpenPositions?: number;
    maxSlippagePips?: number;
  },
): Promise<CopyRelationship> {
  return coreAppFetch<CopyRelationship>(baseUrl, "/api/v1/copy-relationships/from-invitation", {
    method: "POST",
    accessToken,
    body: JSON.stringify({
      invitation_id: input.invitationId,
      follower_broker_account_id: input.followerBrokerAccountId,
      method: input.method ?? null,
      fixed_lot_size: input.fixedLotSize ?? null,
      multiplier: input.multiplier ?? null,
      risk_percent: input.riskPercent ?? null,
      rounding_mode: input.roundingMode ?? null,
      max_lot_per_trade: input.maxLotPerTrade ?? null,
      max_open_positions: input.maxOpenPositions ?? null,
      max_slippage_pips: input.maxSlippagePips ?? null,
    }),
  });
}

// ==================== TICKET-118 follow-up — prospect nominations (Follower -> Master inbox) ====================

/** FOLLOWER-only server-side call — 409s (`no_master_to_nominate_to`) if the caller has no Master yet. */
export async function nominateProspect(
  baseUrl: string,
  accessToken: string,
  prospectEmail: string,
): Promise<void> {
  await coreAppFetch<null>(baseUrl, "/api/v1/prospect-nominations", {
    method: "POST",
    accessToken,
    body: JSON.stringify({ prospect_email: prospectEmail }),
  });
}

/** FOLLOWER-only server-side call — backs the real `/follower/referrals` referral-history table. */
export async function listMyProspectNominations(
  baseUrl: string,
  accessToken: string,
): Promise<MyProspectNomination[]> {
  return coreAppFetch<MyProspectNomination[]>(baseUrl, "/api/v1/prospect-nominations/mine", {
    method: "GET",
    accessToken,
  });
}

/** MASTER-only server-side call — backs the real `/inbox` page. */
export async function listProspectNominations(
  baseUrl: string,
  accessToken: string,
  status?: string,
): Promise<ProspectNomination[]> {
  const query = status ? `?status=${status}` : "";
  return coreAppFetch<ProspectNomination[]>(baseUrl, `/api/v1/master/prospect-nominations${query}`, {
    method: "GET",
    accessToken,
  });
}

/** Call after successfully creating the real invitation via {@link createInvitation}. */
export async function markNominationInvited(
  baseUrl: string,
  accessToken: string,
  nominationId: string,
  invitationId: string,
): Promise<void> {
  await coreAppFetch<null>(baseUrl, `/api/v1/master/prospect-nominations/${nominationId}/mark-invited`, {
    method: "POST",
    accessToken,
    body: JSON.stringify({ invitation_id: invitationId }),
  });
}

export async function dismissNomination(
  baseUrl: string,
  accessToken: string,
  nominationId: string,
): Promise<void> {
  await coreAppFetch<null>(baseUrl, `/api/v1/master/prospect-nominations/${nominationId}/dismiss`, {
    method: "POST",
    accessToken,
  });
}
