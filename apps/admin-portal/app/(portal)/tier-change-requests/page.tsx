import { cookies } from "next/headers";
import Link from "next/link";
import { listTierChangeRequests } from "@nectrix/api-client";
import { coreAppBaseUrl } from "@/lib/core-app";

/** TICKET-122 — ADMIN+SUPPORT+SUPER_ADMIN can view; only the [id] detail page's decide form is
 * restricted further (ADMIN+SUPER_ADMIN), same view/decide split the Disputes page already uses. */
export default async function TierChangeRequestsPage() {
  const jar = await cookies();
  const accessToken = jar.get("access_token")!.value;

  const requests = await listTierChangeRequests(coreAppBaseUrl(), accessToken, "PENDING");

  return (
    <div className="max-w-4xl">
      <h1 className="text-[25px] font-semibold tracking-tight text-[var(--text)]">
        Tier-change requests
      </h1>
      <p className="mt-1.5 text-[14px] text-[var(--text-2)]">
        Individual-mode users requesting Master or Follower access — each must accept an agreement
        before it can be approved.
      </p>

      <h2 className="mt-8 text-[16px] font-semibold text-[var(--text)]">Pending review</h2>
      <div className="mt-3 overflow-hidden rounded-2xl border border-[var(--border)] bg-[var(--surface)]">
        <table className="w-full border-collapse text-[13px]">
          <thead>
            <tr className="border-b border-[var(--border)] text-left">
              {["User", "Target role", "Submitted", ""].map((heading) => (
                <th
                  key={heading}
                  className="px-5 py-2.5 text-[11.5px] font-semibold tracking-wide text-[var(--text-3)] uppercase"
                >
                  {heading}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {requests.length === 0 && (
              <tr>
                <td colSpan={4} className="px-5 py-8 text-center text-[var(--text-3)]">
                  No pending requests.
                </td>
              </tr>
            )}
            {requests.map((request) => (
              <tr key={request.id} className="border-b border-[var(--border)] last:border-0">
                <td className="px-5 py-2.5 font-mono text-[12px] text-[var(--text-2)]">
                  {request.userId}
                </td>
                <td className="px-5 py-2.5 text-[var(--text)]">
                  {request.targetRole === "MASTER" ? "Master" : "Follower"}
                </td>
                <td className="px-5 py-2.5 text-[var(--text-2)]">
                  {new Date(request.createdAt).toLocaleString()}
                </td>
                <td className="px-5 py-2.5 text-right">
                  <Link
                    href={`/tier-change-requests/${request.id}`}
                    className="inline-block rounded-full bg-[var(--surface-2)] px-2.5 py-1 text-[12px] font-semibold text-[var(--text-2)] transition-colors hover:bg-[var(--border)] hover:text-[var(--text)]"
                  >
                    Review
                  </Link>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
