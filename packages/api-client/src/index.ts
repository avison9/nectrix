// TICKET-012 — the first real client here (everything before this was a
// stub). Covers only what apps/admin-portal needs so far: login/refresh and
// the two new admin routes. Every function is meant to be called
// server-side only (Next.js Route Handlers) — the access token these return
// must never reach client JS, see apps/admin-portal's app/api/auth routes.

import type {
  AdminPortalRole,
  AuditLogEntry,
  AuditLogPage,
  NormalizedTradeEvent,
} from "@nectrix/domain-model";

export type { NormalizedTradeEvent, AdminPortalRole, AuditLogEntry, AuditLogPage };

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
