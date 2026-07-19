import Link from "next/link";
import { redirect } from "next/navigation";
import { getPendingInvitation, listBrokerAccounts } from "@nectrix/api-client";
import { coreAppBaseUrl } from "@/lib/core-app";
import { requireSession } from "@/lib/auth";
import { StartCopyingForm } from "./StartCopyingForm";

/**
 * TICKET-118 — onboarding step 4's real destination for an invited Follower: review the inviting
 * Master (defaults are applied server-side unless "customize" is checked — see
 * InvitationCopySetupService's own Javadoc for why suggested profile VALUES aren't fetched and
 * shown inline here: there's no standalone GET for a money-management/risk profile by id anywhere
 * in this API, only nested inside a CopyRelationship's own response), pick which broker account to
 * copy into, and submit `POST /copy-relationships/from-invitation`.
 */
export default async function StartCopyingPage() {
  const { accessToken } = await requireSession();
  const [pendingInvitation, brokerAccounts] = await Promise.all([
    getPendingInvitation(coreAppBaseUrl(), accessToken),
    listBrokerAccounts(coreAppBaseUrl(), accessToken),
  ]);

  if (!pendingInvitation) {
    redirect("/onboarding");
  }

  return (
    <div className="mx-auto max-w-[560px]">
      <Link
        href="/onboarding"
        className="mb-4.5 inline-flex items-center gap-1.5 rounded-[9px] border border-[var(--border)] px-3 py-1.5 text-[13px] font-semibold text-[var(--text-2)] hover:bg-[var(--surface-2)]"
      >
        ← Back to onboarding
      </Link>

      <div className="mb-6">
        <h1 className="text-[23px] font-semibold tracking-tight text-[var(--text)]">
          Start copying {pendingInvitation.masterDisplayName}
        </h1>
        <p className="mt-1.5 text-[14px] text-[var(--text-2)]">
          Unless you customize below, your copy settings use {pendingInvitation.masterDisplayName}
          &apos;s recommended defaults.
        </p>
      </div>

      {brokerAccounts.length === 0 ? (
        <div className="rounded-2xl border border-[var(--border)] bg-[var(--surface)] p-5.5 text-[13.5px] text-[var(--text-2)]">
          You need a linked broker account before you can start copying.{" "}
          <Link href="/broker-accounts/link" className="font-semibold text-[var(--accent)]">
            Open or link a broker account
          </Link>{" "}
          first, then come back here.
        </div>
      ) : (
        <div className="rounded-2xl border border-[var(--border)] bg-[var(--surface)] p-5.5">
          <StartCopyingForm
            invitationId={pendingInvitation.invitationId}
            brokerAccounts={brokerAccounts}
          />
        </div>
      )}
    </div>
  );
}
