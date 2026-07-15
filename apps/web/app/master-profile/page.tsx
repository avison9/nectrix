import { listBrokerAccounts } from "@nectrix/api-client";
import { coreAppBaseUrl } from "@/lib/core-app";
import { requireSession } from "@/lib/auth";
import { CreateMasterProfileForm } from "./CreateMasterProfileForm";

/**
 * TICKET-111 — self-service Master profile creation. There's no "do I
 * already have one" lookup endpoint (only POST, which 409s with
 * existing_profile_id) — see CreateMasterProfileForm's own redirect-on-409
 * handling, matching the ticket's own scope note.
 */
export default async function MasterProfilePage() {
  const { accessToken } = await requireSession();
  const brokerAccounts = await listBrokerAccounts(coreAppBaseUrl(), accessToken);

  return (
    <main className="mx-auto max-w-[480px] px-4 py-10">
      <h1 className="text-[20px] font-semibold tracking-tight text-[var(--text)]">
        Become a Master
      </h1>
      <p className="mt-1.5 text-[13px] text-[var(--text-2)]">
        Set up your public Master profile so Followers can copy your trades.
      </p>

      <CreateMasterProfileForm brokerAccounts={brokerAccounts} />
    </main>
  );
}
