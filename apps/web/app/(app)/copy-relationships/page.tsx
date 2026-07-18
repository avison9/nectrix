import Link from "next/link";
import { listCopyRelationships } from "@nectrix/api-client";
import { coreAppBaseUrl } from "@/lib/core-app";
import { requireSession } from "@/lib/auth";
import { CopyRelationshipStatusBadge } from "@/components/CopyRelationshipStatusBadge";

/**
 * TICKET-111 — follower-facing list only (a Master-side capability list is
 * out of this ticket's scope, see CopyRelationshipService's own Javadoc on
 * ownership scoping).
 */
export default async function CopyRelationshipsPage() {
  const { accessToken } = await requireSession();
  const relationships = await listCopyRelationships(coreAppBaseUrl(), accessToken, {
    role: "follower",
  });

  return (
    <div className="mx-auto max-w-[720px]">
      <h1 className="text-[20px] font-semibold tracking-tight text-[var(--text)]">
        Masters you copy
      </h1>

      {relationships.length === 0 ? (
        <p className="mt-8 text-[13.5px] text-[var(--text-2)]">
          You&apos;re not copying any Masters yet.
        </p>
      ) : (
        <ul className="mt-6 flex flex-col gap-3">
          {relationships.map((cr) => (
            <li key={cr.id}>
              <Link
                href={`/copy-relationships/${cr.id}`}
                className="flex items-center justify-between rounded-[12px] border border-[var(--border)] bg-[var(--surface)] p-4"
              >
                <div className="flex flex-col gap-1">
                  <span className="font-mono text-[13px] text-[var(--text)]">{cr.id}</span>
                  <span className="text-[12.5px] text-[var(--text-2)]">
                    {cr.copyDirection} · fee {cr.feeCollectionMethod}
                  </span>
                </div>
                <CopyRelationshipStatusBadge status={cr.status} />
              </Link>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
