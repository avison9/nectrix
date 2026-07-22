import Link from "next/link";
import { getMyTierChangeRequest } from "@nectrix/api-client";
import { coreAppBaseUrl } from "@/lib/core-app";
import { requireSession } from "@/lib/auth";
import { TierChangeRequestForm } from "./TierChangeRequestForm";

// TICKET-122 — no Nectrix.dc.html mock section exists for this page (confirmed absent from the
// mock during planning; the ticket's own "Design references" line says as much). Built as
// functional UI in this app's established visual language (Tailwind + the same CSS-var tokens/
// rounded-card conventions every other (app) page already uses, e.g. app/(app)/commission/
// fee-reports), same "no mock, build honestly rather than invent one" precedent TICKET-120's own
// fee-report UI already established this session.
export default async function TierChangePage() {
  const { session, accessToken } = await requireSession();
  const alreadyElevated = session.roles.includes("MASTER") || session.roles.includes("FOLLOWER");

  const myRequest = alreadyElevated
    ? null
    : await getMyTierChangeRequest(coreAppBaseUrl(), accessToken);

  return (
    <div className="mx-auto max-w-[640px]">
      <div className="mb-6">
        <h1 className="text-[25px] font-semibold tracking-tight text-[var(--text)]">
          Account tier
        </h1>
        <p className="mt-1.5 text-sm text-[var(--text-2)]">
          Individual accounts start with no Master/Follower access — request a tier change to
          unlock either.
        </p>
      </div>

      {alreadyElevated && (
        <div className="rounded-2xl border border-[var(--border)] bg-[var(--surface)] p-5.5 text-[13.5px] text-[var(--text-2)]">
          Your account already has {session.roles.includes("MASTER") ? "Master" : "Follower"}{" "}
          access — there&rsquo;s nothing to request.{" "}
          <Link href="/profile" className="font-medium text-[var(--accent)]">
            Back to profile
          </Link>
        </div>
      )}

      {!alreadyElevated && myRequest?.status === "PENDING" && (
        <div className="rounded-2xl border border-[var(--border)] bg-[var(--surface)] p-5.5">
          <div className="mb-1 flex items-center gap-2">
            <span className="rounded-full bg-[var(--surface-2)] px-2.5 py-1 text-[12px] font-semibold text-[var(--text-2)]">
              Pending review
            </span>
          </div>
          <p className="text-[13.5px] text-[var(--text-2)]">
            Your request to become a{" "}
            {myRequest.targetRole === "MASTER" ? "Master" : "Follower"} is awaiting Admin review.
            We&rsquo;ll notify you once it&rsquo;s decided.
          </p>
        </div>
      )}

      {!alreadyElevated && myRequest?.status === "REJECTED" && (
        <>
          <div className="mb-4 rounded-2xl border border-[var(--border)] bg-[var(--surface)] p-5.5">
            <div className="mb-1 flex items-center gap-2">
              <span className="rounded-full bg-[var(--neg)]/15 px-2.5 py-1 text-[12px] font-semibold text-[var(--neg)]">
                Rejected
              </span>
            </div>
            <p className="text-[13.5px] text-[var(--text-2)]">
              Your request to become a{" "}
              {myRequest.targetRole === "MASTER" ? "Master" : "Follower"} was rejected
              {myRequest.reviewReason ? `: ${myRequest.reviewReason}` : "."}
            </p>
          </div>
          <TierChangeRequestForm />
        </>
      )}

      {!alreadyElevated && (myRequest === null || myRequest.status === "APPROVED") && (
        <TierChangeRequestForm />
      )}
    </div>
  );
}
