import type { ConnectionRole } from "@nectrix/api-client";
import { requireSession } from "@/lib/auth";
import { CtraderCallbackClient } from "./CtraderCallbackClient";

/**
 * TICKET-101 follow-up — the account-role picker used to be a free-choice dropdown regardless of
 * the caller's own account mode, letting a Follower-mode session link an account as MASTER_ONLY (or
 * vice versa), which never made sense — a Follower's own broker account can only ever copy FROM
 * someone, not be copied. Determined server-side from the session's own roles, same MASTER-takes-
 * priority-when-both-held precedent `dashboard/page.tsx` already established, and the same "neither
 * role held" signal `IndividualModeCapabilityGuard`/`IndividualCopySetupService` already use for
 * Individual mode (whose own self-copying scenario is exactly BOTH's real use case).
 */
export default async function CtraderCallbackPage() {
  const { session } = await requireSession();
  const accountRole: ConnectionRole = session.roles.includes("MASTER")
    ? "MASTER_ONLY"
    : session.roles.includes("FOLLOWER")
      ? "FOLLOWER_ONLY"
      : "BOTH";

  return <CtraderCallbackClient accountRole={accountRole} />;
}
