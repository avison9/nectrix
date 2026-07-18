import { requireSession } from "@/lib/auth";
import { MasterDashboard } from "./MasterDashboard";
import { FollowerDashboard } from "./FollowerDashboard";

/**
 * TICKET-116 — the mock's two distinct, non-overlapping dashboard screens (`vMasterDash`/
 * `vFollowerDash`), gated on role rather than a client-side toggle. A caller holding both MASTER
 * and FOLLOWER (rare, but not disallowed by the account model) sees the Master view — same
 * precedent Sidebar's own nav-item logic already establishes (MASTER-gated items are additive, not
 * exclusive, but this one page has to pick one primary view).
 */
export default async function DashboardPage() {
  const { session, accessToken } = await requireSession();

  return session.roles.includes("MASTER") ? (
    <MasterDashboard accessToken={accessToken} />
  ) : (
    <FollowerDashboard email={session.email} accessToken={accessToken} />
  );
}
