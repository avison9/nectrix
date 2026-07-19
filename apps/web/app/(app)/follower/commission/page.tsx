import Link from "next/link";
import { listMySettlements } from "@nectrix/api-client";
import { coreAppBaseUrl } from "@/lib/core-app";
import { requireSession } from "@/lib/auth";

const SAMPLE_EARN_ROWS = [
  { initials: "CY", who: "Chris Yang", profit: "+$620", fee: "$124", earn: "+$3.72" },
  { initials: "NF", who: "Nadia Farouk", profit: "+$310", fee: "$62", earn: "+$1.86" },
];

/**
 * Mirrors Nectrix.dc.html's `FOLLOWER · COMMISSION VIEW` (`vFollowerCommission`, `:1567-1628`).
 * TICKET-117 follow-up — "Settlement history" is now real (FeeLedgerController's self-service
 * fee-ledger endpoints), each row linking to a real per-settlement detail page with the full
 * computation breakdown and a "Raise a dispute" action. The referral-earnings half is still Phase
 * 2 (TICKET-207) sample data — a genuinely separate feature (referral commission, not the
 * performance-fee settlement this page's own title covers) that hasn't shipped anywhere yet.
 */
export default async function FollowerCommissionPage() {
  const { accessToken } = await requireSession();
  const settlements = await listMySettlements(coreAppBaseUrl(), accessToken);

  return (
    <div className="mx-auto max-w-[1000px]">
      <div className="mb-6">
        <h1 className="text-[25px] font-semibold tracking-tight text-[var(--text)]">
          Commission &amp; fees
        </h1>
        <p className="mt-1.5 text-[14px] text-[var(--text-2)]">
          Your referral commission is set by your master — a share of the performance fee charged on
          accounts you refer. You pay a performance fee on <strong>profit only</strong>; losing
          months are never billed.
        </p>
      </div>

      <div className="mb-4 grid grid-cols-1 gap-4 lg:grid-cols-[1.3fr_1fr]">
        <div className="overflow-hidden rounded-2xl border border-[var(--border)] bg-[var(--surface)]">
          <div className="border-b border-[var(--border)] px-5 py-3.5 text-[14px] font-semibold text-[var(--text)]">
            Settlement history
          </div>
          <div className="flex flex-col">
            {settlements.length === 0 && (
              <p className="px-5 py-6 text-[13px] text-[var(--text-3)]">
                No settlements yet — this fills in once your first billing period closes.
              </p>
            )}
            {settlements.map((r) => (
              <Link
                key={r.id}
                href={`/follower/commission/${r.id}`}
                className="flex items-center gap-3.5 border-t border-[var(--border)] px-5 py-3.5 first:border-t-0 hover:bg-[var(--surface-2)]"
              >
                <div className="flex-1">
                  <div className="text-[13.5px] font-semibold text-[var(--text)]">
                    {new Date(r.periodStart).toLocaleDateString(undefined, {
                      month: "short",
                      year: "numeric",
                    })}
                  </div>
                  <span
                    className={`mt-0.5 inline-block rounded-full px-2 py-0.5 text-[11px] font-semibold ${
                      r.status === "DISPUTED"
                        ? "bg-[var(--neg)]/15 text-[var(--neg)]"
                        : r.status === "VOID"
                          ? "bg-[var(--surface-2)] text-[var(--text-3)]"
                          : "bg-[var(--pos)]/15 text-[var(--pos)]"
                    }`}
                  >
                    {r.status}
                  </span>
                </div>
                <div className="w-20 text-right">
                  <div className="text-[11px] text-[var(--text-3)]">Fee</div>
                  <div className="mt-0.5 font-mono text-[13.5px] font-semibold text-[var(--accent)]">
                    ${r.masterFeeAmount.toFixed(2)}
                  </div>
                </div>
              </Link>
            ))}
          </div>
        </div>

        <div className="flex flex-col gap-4">
          <div className="rounded-2xl bg-[var(--accent-2)] p-5">
            <div className="text-[12.5px] font-semibold text-[var(--accent)]">Referral commission</div>
            <div className="mt-2 font-mono text-[32px] font-semibold tracking-tight text-[var(--accent)]">
              3.0%
            </div>
            <div className="mt-1 text-[12.5px] leading-[1.5] text-[var(--accent)]">
              of your master&apos;s 20% performance fee.
            </div>
          </div>
          <div className="rounded-2xl border border-[var(--border)] bg-[var(--surface)] p-5">
            <div className="text-[12.5px] font-medium text-[var(--text-2)]">
              Performance fee you pay
            </div>
            <div className="mt-2 font-mono text-[26px] font-semibold tracking-tight text-[var(--text)]">
              20%
            </div>
            <div className="mt-1 text-[12.5px] text-[var(--text-3)]">
              of net new profit · high-water mark · profit only
            </div>
          </div>
        </div>
      </div>

      <div className="overflow-hidden rounded-2xl border border-[var(--border)] bg-[var(--surface)] opacity-70">
        <div className="flex flex-wrap items-center justify-between gap-2 border-b border-[var(--border)] px-5 py-3.5">
          <span className="text-[14px] font-semibold text-[var(--text)]">
            Referral earnings this month
          </span>
          <span className="text-[13px] text-[var(--text-2)]">
            Total <strong className="font-mono text-[var(--pos)]">+$5.58</strong>
          </span>
        </div>
        <div className="overflow-x-auto">
          <table className="w-full min-w-[480px] border-collapse">
            <thead>
              <tr>
                <th className="px-5 py-2.5 text-left text-[11.5px] font-semibold uppercase tracking-wide text-[var(--text-3)]">
                  Referred account
                </th>
                <th className="px-5 py-2.5 text-right text-[11.5px] font-semibold uppercase tracking-wide text-[var(--text-3)]">
                  Profit
                </th>
                <th className="px-5 py-2.5 text-right text-[11.5px] font-semibold uppercase tracking-wide text-[var(--text-3)]">
                  Master fee
                </th>
                <th className="px-5 py-2.5 text-right text-[11.5px] font-semibold uppercase tracking-wide text-[var(--text-3)]">
                  Your rebate
                </th>
              </tr>
            </thead>
            <tbody>
              {SAMPLE_EARN_ROWS.map((r) => (
                <tr key={r.who} className="border-t border-[var(--border)]">
                  <td className="px-5 py-3">
                    <div className="flex items-center gap-2.5">
                      <div className="flex h-[30px] w-[30px] shrink-0 items-center justify-center rounded-full bg-[var(--surface-2)] text-[11px] font-semibold text-[var(--text-2)]">
                        {r.initials}
                      </div>
                      <span className="whitespace-nowrap text-[13.5px] font-medium text-[var(--text)]">
                        {r.who}
                      </span>
                    </div>
                  </td>
                  <td className="whitespace-nowrap px-5 py-3 text-right font-mono text-[13px] font-semibold text-[var(--pos)]">
                    {r.profit}
                  </td>
                  <td className="whitespace-nowrap px-5 py-3 text-right font-mono text-[13px] text-[var(--text-2)]">
                    {r.fee}
                  </td>
                  <td className="whitespace-nowrap px-5 py-3 text-right font-mono text-[13px] font-semibold text-[var(--pos)]">
                    {r.earn}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  );
}
