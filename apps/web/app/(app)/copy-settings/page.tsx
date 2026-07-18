import Link from "next/link";
import { redirect } from "next/navigation";
import { listCopyRelationships } from "@nectrix/api-client";
import { coreAppBaseUrl } from "@/lib/core-app";
import { requireSession } from "@/lib/auth";
import { CopyRelationshipStatusBadge } from "@/components/CopyRelationshipStatusBadge";

/**
 * Mirrors Nectrix.dc.html's `FOLLOWER · COPY SETTINGS` (`vFollowerCopy`, `:1483-1515`) as a nav-
 * level shortcut into the real, already-built per-relationship edit form
 * (`copy-relationships/[id]/CopySettingsForm.tsx`, TICKET-116 #302) — no new backend. Exactly one
 * relationship redirects straight to its detail page; more than one shows a chooser (the mock's own
 * screen only ever assumes a single master).
 */
export default async function CopySettingsPage() {
  const { accessToken } = await requireSession();
  const relationships = await listCopyRelationships(coreAppBaseUrl(), accessToken, { role: "follower" });

  if (relationships.length === 1) {
    redirect(`/copy-relationships/${relationships[0]!.id}`);
  }

  return (
    <div className="mx-auto max-w-[720px]">
      <div className="mb-6">
        <h1 className="text-[25px] font-semibold tracking-tight text-[var(--text)]">
          Copy settings
        </h1>
        <p className="mt-1.5 text-[14px] text-[var(--text-2)]">
          Control how each master&apos;s trades are sized and risk-managed on your account.
        </p>
      </div>

      {relationships.length === 0 ? (
        <p className="mt-4 text-[13.5px] text-[var(--text-2)]">
          You&apos;re not copying a master yet.{" "}
          <Link href="/masters" className="text-[var(--accent)] underline-offset-2 hover:underline">
            Find a master to follow
          </Link>
          .
        </p>
      ) : (
        <div className="flex flex-col gap-3">
          {relationships.map((r) => (
            <Link
              key={r.id}
              href={`/copy-relationships/${r.id}`}
              className="flex items-center justify-between rounded-[14px] border border-[var(--border)] bg-[var(--surface)] p-4 transition-colors hover:bg-[var(--surface-2)]"
            >
              <div>
                <div className="text-[14px] font-medium text-[var(--text)]">
                  {r.moneyManagementProfile.method} · {r.copyDirection}
                </div>
                <div className="mt-0.5 font-mono text-[12px] text-[var(--text-3)]">{r.id}</div>
              </div>
              <CopyRelationshipStatusBadge status={r.status} />
            </Link>
          ))}
        </div>
      )}
    </div>
  );
}
