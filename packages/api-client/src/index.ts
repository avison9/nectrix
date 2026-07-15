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
  BrokerIbLink,
  BrokerType,
  CopiedTrade,
  CopiedTradesPage,
  ConnectionRole,
  ConnectionStatus,
  CopyRelationship,
  CopyRelationshipStatus,
  CtraderAccountOption,
  FeeCollectionMethod,
  MasterProfile,
  NormalizedPosition,
  NormalizedTradeEvent,
  SymbolMappingEntry,
} from "@nectrix/domain-model";

export type {
  NormalizedTradeEvent,
  AdminPortalRole,
  AuditLogEntry,
  AuditLogPage,
  BrokerAccountSnapshot,
  BrokerAccountSummary,
  BrokerIbLink,
  BrokerType,
  CopiedTrade,
  CopiedTradesPage,
  ConnectionRole,
  ConnectionStatus,
  CopyRelationship,
  CopyRelationshipStatus,
  CtraderAccountOption,
  FeeCollectionMethod,
  MasterProfile,
  SymbolMappingEntry,
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
// that. Shallow-plus-one-level (objects, and arrays of objects) is enough for
// every shape this client currently parses — no nested object graphs yet.
function mapKeysShallow<T>(value: unknown): T {
  if (Array.isArray(value)) {
    return value.map((item) => mapKeysShallow(item)) as unknown as T;
  }
  if (value !== null && typeof value === "object") {
    const result: Record<string, unknown> = {};
    for (const [key, val] of Object.entries(value as Record<string, unknown>)) {
      result[snakeToCamel(key)] = Array.isArray(val) ? mapKeysShallow(val) : val;
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

export interface ProvisionedUser {
  id: string;
}

/** ADMIN-only server-side call (TICKET-012's account-provisioning form). */
export async function createAdminUser(
  baseUrl: string,
  accessToken: string,
  input: { email: string; password: string; displayName: string; role: "ADMIN" | "SUPPORT" },
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
 * Returns [] gracefully when a Master has no active IB links (TICKET-119 isn't built yet — this
 * is TICKET-110's own narrow, additive read, see BrokerIbLinkController's Javadoc).
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
