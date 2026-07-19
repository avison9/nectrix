import { cookies } from "next/headers";
import Link from "next/link";
import { listDisputedLedgerEntries } from "@nectrix/api-client";
import { coreAppBaseUrl } from "@/lib/core-app";
import { RaiseDisputeForm } from "./RaiseDisputeForm";

/** TICKET-117 — replaces the StubPage placeholder with a real list of DISPUTED ledger rows. */
export default async function DisputesPage() {
  const jar = await cookies();
  const accessToken = jar.get("access_token")!.value;

  const entries = await listDisputedLedgerEntries(coreAppBaseUrl(), accessToken);

  return (
    <div className="max-w-4xl">
      <h1 className="text-[25px] font-semibold tracking-tight text-[var(--text)]">Disputes</h1>
      <p className="mt-1.5 text-[14px] text-[var(--text-2)]">
        Performance-fee ledger disputes — uphold/adjust/void resolutions against audited
        compensating records, never a silent edit to the original computation.
      </p>

      <div className="mt-6">
        <RaiseDisputeForm />
      </div>

      <h2 className="mt-8 text-[16px] font-semibold text-[var(--text)]">Open disputes</h2>
      <div className="mt-3 overflow-hidden rounded-2xl border border-[var(--border)] bg-[var(--surface)]">
        <table className="w-full border-collapse text-[13px]">
          <thead>
            <tr className="border-b border-[var(--border)] text-left">
              {["Period", "Copy relationship", "Master fee", "Net to master", ""].map(
                (heading) => (
                  <th
                    key={heading}
                    className="px-5 py-2.5 text-[11.5px] font-semibold tracking-wide text-[var(--text-3)] uppercase"
                  >
                    {heading}
                  </th>
                ),
              )}
            </tr>
          </thead>
          <tbody>
            {entries.length === 0 && (
              <tr>
                <td colSpan={5} className="px-5 py-8 text-center text-[var(--text-3)]">
                  No open disputes.
                </td>
              </tr>
            )}
            {entries.map((entry) => (
              <tr key={entry.id} className="border-b border-[var(--border)] last:border-0">
                <td className="px-5 py-2.5 text-[var(--text-2)]">
                  {new Date(entry.periodStart).toLocaleDateString()} –{" "}
                  {new Date(entry.periodEnd).toLocaleDateString()}
                </td>
                <td className="px-5 py-2.5 font-mono text-[12px] text-[var(--text-2)]">
                  {entry.copyRelationshipId}
                </td>
                <td className="px-5 py-2.5 font-mono text-[var(--text)]">
                  ${entry.masterFeeAmount.toFixed(2)}
                </td>
                <td className="px-5 py-2.5 font-mono text-[var(--text)]">
                  ${entry.netToMasterAmount.toFixed(2)}
                </td>
                <td className="px-5 py-2.5 text-right">
                  <Link
                    href={`/disputes/${entry.id}`}
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
