import { ApiError, getInvitationByToken, type InvitationPreview } from "@nectrix/api-client";
import { coreAppBaseUrl } from "@/lib/core-app";
import { AcceptInviteForm } from "./AcceptInviteForm";

async function loadPreview(token: string): Promise<InvitationPreview | "invalid"> {
  try {
    return await getInvitationByToken(coreAppBaseUrl(), token);
  } catch (error) {
    if (error instanceof ApiError && error.status === 400) {
      return "invalid";
    }
    throw error;
  }
}

/**
 * TICKET-118 — the entry point a Follower actually lands on from their invitation email: public,
 * no session required (see proxy.ts's PUBLIC_PATHS). Reads `?token=`, previews the real invitation
 * (inviting Master's name, invited email, expiry) via the public `GET /invitations/by-token/{token}`
 * before rendering the accept UI — an invalid/expired/revoked/already-accepted token shows one
 * generic message, matching that endpoint's own no-leak AC.
 */
export default async function AcceptInvitePage({
  searchParams,
}: {
  searchParams: Promise<{ token?: string }>;
}) {
  const { token } = await searchParams;

  if (!token) {
    return (
      <Shell title="Invitation link missing">
        <p className="text-[13.5px] leading-[1.5] text-[var(--text-2)]">
          This link is missing its invitation token — please use the exact link from your invite
          email.
        </p>
      </Shell>
    );
  }

  const preview = await loadPreview(token);

  if (preview === "invalid") {
    return (
      <Shell title="This invitation is invalid or has expired">
        <p className="text-[13.5px] leading-[1.5] text-[var(--text-2)]">
          Ask your Master to send you a new invitation.
        </p>
      </Shell>
    );
  }

  return (
    <Shell
      title="You've been invited to copy trade"
      subtitle={
        preview.masterDisplayName
          ? `${preview.masterDisplayName} invited ${preview.invitedEmail} to Nectrix.`
          : `You've been invited to Nectrix as ${preview.invitedEmail}.`
      }
    >
      <AcceptInviteForm token={token} />
    </Shell>
  );
}

function Shell({
  title,
  subtitle,
  children,
}: {
  title: string;
  subtitle?: string;
  children: React.ReactNode;
}) {
  return (
    <main className="flex min-h-screen items-center justify-center bg-[var(--bg)] px-4 py-10">
      <div className="w-full max-w-[440px]">
        <div className="mb-[26px] flex flex-col items-center text-center">
          <h1 className="text-[22px] font-semibold tracking-tight text-[var(--text)]">{title}</h1>
          {subtitle && (
            <p className="mt-2 text-[13.5px] leading-[1.5] text-[var(--text-2)]">{subtitle}</p>
          )}
        </div>
        <div className="rounded-2xl border border-[var(--border)] bg-[var(--surface)] p-6">
          {children}
        </div>
      </div>
    </main>
  );
}
