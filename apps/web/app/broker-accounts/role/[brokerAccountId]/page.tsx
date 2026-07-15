import { getBrokerAccount } from "@nectrix/api-client";
import { coreAppBaseUrl } from "@/lib/core-app";
import { requireSession } from "@/lib/auth";
import { fetchOrNotFound } from "@/lib/fetchOrNotFound";
import { RoleSelectionForm } from "./RoleSelectionForm";

/**
 * docs/07-auth-onboarding-broker-linking.md §7.5's flowchart's final step:
 * "User confirms account role: Master-only / Follower-only / Both — set by
 * the platform to FOLLOWER_ONLY by default for invite-created accounts."
 */
export default async function RoleSelectionPage({
  params,
}: {
  params: Promise<{ brokerAccountId: string }>;
}) {
  const { accessToken } = await requireSession();
  const { brokerAccountId } = await params;
  const account = await fetchOrNotFound(
    getBrokerAccount(coreAppBaseUrl(), accessToken, brokerAccountId),
  );

  return (
    <main className="mx-auto max-w-[420px] px-4 py-10">
      <h1 className="text-[20px] font-semibold tracking-tight text-[var(--text)]">
        Choose the account&apos;s role
      </h1>
      <p className="mt-1.5 text-[13px] text-[var(--text-2)]">
        {account.displayLabel ?? account.brokerAccountLogin} — {account.brokerType}
      </p>

      <RoleSelectionForm brokerAccountId={brokerAccountId} initialRole={account.connectionRole} />
    </main>
  );
}
