import type { ConnectionRole } from "@nectrix/api-client";
import { requireSession } from "@/lib/auth";
import { Mt5LinkClient } from "./Mt5LinkClient";

/**
 * TICKET-101 follow-up — same fix as the cTrader callback page's own Javadoc: account role is
 * determined server-side from the caller's own account mode, not a free-choice dropdown.
 */
export default async function ConnectMt5Page() {
  const { session } = await requireSession();
  const accountRole: ConnectionRole = session.roles.includes("MASTER")
    ? "MASTER_ONLY"
    : session.roles.includes("FOLLOWER")
      ? "FOLLOWER_ONLY"
      : "BOTH";

  return <Mt5LinkClient accountRole={accountRole} />;
}
