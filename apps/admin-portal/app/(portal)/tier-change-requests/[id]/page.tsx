import { cookies } from "next/headers";
import Link from "next/link";
import { getTierChangeRequest } from "@nectrix/api-client";
import { coreAppBaseUrl } from "@/lib/core-app";
import { verifyAccessToken } from "@/lib/session";
import { DecideTierChangeRequestForm } from "../DecideTierChangeRequestForm";

/** TICKET-122 — request detail + the approve/reject form (ADMIN+SUPER_ADMIN only — SUPPORT can view). */
export default async function TierChangeRequestDetailPage({
  params,
}: {
  params: Promise<{ id: string }>;
}) {
  const { id } = await params;
  const jar = await cookies();
  const token = jar.get("access_token")?.value;
  const session = token ? await verifyAccessToken(token) : null;
  const canDecide = !!session?.roles.some((r) => r === "ADMIN" || r === "SUPER_ADMIN");
  const accessToken = jar.get("access_token")!.value;

  const request = await getTierChangeRequest(coreAppBaseUrl(), accessToken, id);

  return (
    <div className="max-w-3xl">
      <Link
        href="/tier-change-requests"
        className="mb-4 inline-flex items-center gap-1.5 text-[13px] font-medium text-[var(--text-2)] hover:text-[var(--accent)]"
      >
        ← Back to tier-change requests
      </Link>

      <div className="flex flex-wrap items-center justify-between gap-3">
        <h1 className="text-[23px] font-semibold tracking-tight text-[var(--text)]">
          {request.targetRole === "MASTER" ? "Become a Master" : "Become a Follower"}
        </h1>
        <span
          className={`rounded-full px-2.5 py-1 text-[12px] font-semibold ${
            request.status === "PENDING"
              ? "bg-[var(--surface-2)] text-[var(--text-2)]"
              : request.status === "REJECTED"
                ? "bg-[var(--neg)]/15 text-[var(--neg)]"
                : "bg-[var(--pos)]/15 text-[var(--pos)]"
          }`}
        >
          {request.status}
        </span>
      </div>

      <div className="mt-4 overflow-hidden rounded-2xl border border-[var(--border)] bg-[var(--surface)]">
        <table className="w-full border-collapse text-[13px]">
          <tbody>
            <tr className="border-b border-[var(--border)]">
              <td className="px-5 py-2.5 text-[var(--text-2)]">User</td>
              <td className="px-5 py-2.5 text-right font-mono text-[var(--text)]">
                {request.userId}
              </td>
            </tr>
            <tr className="border-b border-[var(--border)]">
              <td className="px-5 py-2.5 text-[var(--text-2)]">Agreement version</td>
              <td className="px-5 py-2.5 text-right font-mono text-[var(--text)]">
                {request.agreementVersion}
              </td>
            </tr>
            <tr className="border-b border-[var(--border)]">
              <td className="px-5 py-2.5 text-[var(--text-2)]">Agreement accepted</td>
              <td className="px-5 py-2.5 text-right text-[var(--text)]">
                {new Date(request.agreementAcceptedAt).toLocaleString()}
              </td>
            </tr>
            <tr className="border-b border-[var(--border)] last:border-0">
              <td className="px-5 py-2.5 text-[var(--text-2)]">Submitted</td>
              <td className="px-5 py-2.5 text-right text-[var(--text)]">
                {new Date(request.createdAt).toLocaleString()}
              </td>
            </tr>
            {request.reviewedAt && (
              <tr className="border-b border-[var(--border)] last:border-0">
                <td className="px-5 py-2.5 text-[var(--text-2)]">Reviewed</td>
                <td className="px-5 py-2.5 text-right text-[var(--text)]">
                  {new Date(request.reviewedAt).toLocaleString()}
                </td>
              </tr>
            )}
            {request.reviewReason && (
              <tr className="last:border-0">
                <td className="px-5 py-2.5 text-[var(--text-2)]">Reason</td>
                <td className="px-5 py-2.5 text-right text-[var(--text)]">
                  {request.reviewReason}
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>

      {canDecide && request.status === "PENDING" && (
        <div className="mt-6">
          <DecideTierChangeRequestForm id={request.id} />
        </div>
      )}
      {request.status !== "PENDING" && (
        <div className="mt-6 rounded-2xl border border-[var(--border)] bg-[var(--surface)] p-5 text-[13.5px] text-[var(--text-2)]">
          This request has been decided — current status is{" "}
          <span className="font-semibold text-[var(--text)]">{request.status}</span>.
        </div>
      )}
    </div>
  );
}
