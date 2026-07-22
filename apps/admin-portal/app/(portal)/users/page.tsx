import { cookies } from "next/headers";
import { getUserStatusCounts } from "@nectrix/api-client";
import { coreAppBaseUrl } from "@/lib/core-app";
import { verifyAccessToken } from "@/lib/session";
import { ProvisionUserForm } from "./ProvisionUserForm";
import { UserSearch } from "./UserSearch";

// TICKET-117 — extends TICKET-012's provisioning-only page with a real search + suspend/
// reinstate directory (previously a "coming soon" placeholder).
export default async function UsersPage() {
  const jar = await cookies();
  const token = jar.get("access_token")?.value;
  const session = token ? await verifyAccessToken(token) : null;
  const isAdmin = !!session?.roles.includes("ADMIN");
  // TICKET-117 follow-up — the summary card in the header row below; zeros if the session
  // somehow has no token (layout.tsx already redirects that case before this ever renders).
  const counts = token
    ? await getUserStatusCounts(coreAppBaseUrl(), token)
    : { total: 0, active: 0, suspended: 0, deleted: 0 };

  return (
    // Bugfix — was max-w-3xl, a FIXED cap that clipped the Suspend/Reinstate + Delete actions
    // column (the rounded-2xl table wrapper below uses overflow-hidden for its corner-rounding,
    // which also silently clips anything past the cap, with no scrollbar to reach it). The
    // correct fix isn't "no cap at all" (that stretches everything to the full page width
    // regardless of content) — it's `flex w-fit flex-col`: a shrink-to-fit container whose own
    // width is driven by its widest child's natural content width (here, the table's own
    // Email/Name/Status/2FA/Actions columns at THEIR natural, unclipped widths), with
    // `align-items: stretch` (flex's default) then making every other child — including
    // ProvisionUserForm's card above — match that same resolved width, so both frames stay in
    // sync with each other and with the table's own longest row, never wider than necessary.
    // (A plain max-w-5xl cap was tried in between as a lower-risk fallback while investigating
    // an unrelated button-unresponsiveness report; that report turned out to be a browser-level
    // window.confirm() suppression issue, unrelated to this layout, so this reverts to the
    // originally-intended fit-content behavior — see UserActions.tsx's own bugfix comment.)
    <div className="relative flex w-fit min-w-[640px] flex-col">
      <h1 className="text-[25px] font-semibold tracking-tight text-[var(--text)]">Users</h1>
      <p className="mt-1.5 text-[14px] text-[var(--text-2)]">
        Provision platform Admin/Support accounts — the only way one comes into existence, since
        there is no self-registration anywhere on Nectrix.
      </p>

      {/* TICKET-117 follow-up — same shape as system-health/page.tsx's own "Copy Engine (last
          15m)" card (label + a row of big-number/caption stat pairs). Absolutely positioned
          against the page container's own `relative` (the one addition above this comment;
          everything else in this file is untouched) so it sits in the upper-right corner
          without joining the normal document flow — the existing header/form/table below are
          not wrapped, reflowed, or otherwise touched by this. */}
      <div className="absolute top-0 right-0 w-fit shrink-0 rounded-2xl border border-[var(--border)] bg-[var(--surface)] p-5">
        <h2 className="text-[13px] font-semibold uppercase tracking-wide text-[var(--text-3)]">
          User accounts
        </h2>
        <div className="mt-4 flex items-end gap-5">
          <div>
            <div className="text-[26px] font-semibold text-[var(--text)]">{counts.total}</div>
            <div className="mt-0.5 text-[12px] text-[var(--text-3)]">Total</div>
          </div>
          <div>
            <div className="text-[26px] font-semibold text-[var(--pos)]">{counts.active}</div>
            <div className="mt-0.5 text-[12px] text-[var(--text-3)]">Active</div>
          </div>
          <div>
            <div className="text-[26px] font-semibold text-[var(--neg)]">{counts.suspended}</div>
            <div className="mt-0.5 text-[12px] text-[var(--text-3)]">Suspended</div>
          </div>
          <div>
            <div className="text-[26px] font-semibold text-[var(--text-3)]">
              {counts.deleted}
            </div>
            <div className="mt-0.5 text-[12px] text-[var(--text-3)]">Deleted</div>
          </div>
        </div>
      </div>

      <div className="mt-6">
        <ProvisionUserForm />
      </div>

      <h2 className="mt-8 text-[16px] font-semibold text-[var(--text)]">All users</h2>
      <div className="mt-3">
        <UserSearch isAdmin={isAdmin} />
      </div>
    </div>
  );
}
