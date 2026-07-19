import Link from "next/link";
import { listMySettlements } from "@nectrix/api-client";
import { coreAppBaseUrl } from "@/lib/core-app";
import { requireSession } from "@/lib/auth";

const SAMPLE_TIERS = [
  { range: "$0 – $10,000 profit", fee: "20%" },
  { range: "$10,000 – $50,000 profit", fee: "17.5%" },
  { range: "$50,000+ profit", fee: "15%" },
];

/**
 * Mirrors Nectrix.dc.html's `MASTER · COMMISSION` (`vMasterCommission`, `:861-945`). TICKET-120
 * (broker fee reports/agreements, the fee-rate *setup* form below) hasn't been built anywhere
 * yet — that section mirrors the mock's own static sample fee schedule and stays inert. TICKET-117
 * follow-up added the real "Settlement history" section beneath it: real
 * performance_fee_ledger rows for this Master's own relationships, each linking to a detail page
 * with a "Raise a dispute" action — the fee engine itself (HWM, profit-only billing) has been real
 * and live server-side since TICKET-113.
 */
export default async function MasterCommissionPage() {
  const { accessToken } = await requireSession();
  const settlements = await listMySettlements(coreAppBaseUrl(), accessToken);

  return (
    <div className="mx-auto max-w-[1000px]">
      <div className="mb-6">
        <h1 className="text-[25px] font-semibold tracking-tight text-[var(--text)]">
          Commission setup
        </h1>
        <p className="mt-1.5 text-[14px] text-[var(--text-2)]">
          Performance fees are charged on <strong>profit only</strong> and deducted by the broker at
          settlement. Losing periods are never billed.
        </p>
      </div>

      <p
        className="mb-4 text-[12.5px] text-[var(--text-3)]"
        title="Commission setup UI isn't available yet — TICKET-120 hasn't shipped"
      >
        Showing the mock&apos;s sample schedule — this page isn&apos;t wired up to real fee
        configuration yet. The underlying fee engine (high-water mark, profit-only billing) is real
        and already live.
      </p>

      <div className="grid grid-cols-1 gap-4 opacity-70 lg:grid-cols-[1.3fr_1fr]">
        <div className="flex flex-col gap-5 rounded-2xl border border-[var(--border)] bg-[var(--surface)] p-5.5">
          <div className="flex items-center justify-between">
            <span className="text-[13.5px] font-semibold text-[var(--text)]">
              Base performance fee
            </span>
            <div className="flex items-center gap-1.5">
              <input
                disabled
                value="20"
                className="h-9 w-16 rounded-[9px] border border-[var(--border)] bg-transparent px-2.5 text-right font-mono text-[16px] font-semibold text-[var(--text)] outline-none"
              />
              <span className="font-mono text-[16px] font-semibold text-[var(--accent)]">%</span>
            </div>
          </div>

          <div className="flex flex-col">
            {[
              { label: "High-water mark", desc: "Only bill above previous equity peak", on: true },
              { label: "Billing cycle", desc: "Settlement frequency", value: "Monthly" },
              { label: "Broker deduction", desc: "Auto-deduct fee from follower profit", on: true },
            ].map((row) => (
              <div
                key={row.label}
                className="flex items-center justify-between border-t border-[var(--border)] py-3.5 first:border-t-0"
              >
                <div>
                  <div className="text-[13.5px] font-medium text-[var(--text)]">{row.label}</div>
                  <div className="mt-0.5 text-[12px] text-[var(--text-3)]">{row.desc}</div>
                </div>
                {"on" in row ? (
                  <div className="relative h-6 w-[42px] shrink-0 rounded-full bg-[var(--accent)]">
                    <div className="absolute left-5 top-0.5 h-5 w-5 rounded-full bg-white" />
                  </div>
                ) : (
                  <span className="rounded-[9px] bg-[var(--surface-2)] px-3 py-1 text-[12px] font-medium text-[var(--text)]">
                    {row.value}
                  </span>
                )}
              </div>
            ))}
          </div>

          <button
            type="button"
            disabled
            title="Commission setup UI isn't available yet — TICKET-120 hasn't shipped"
            className="w-fit cursor-not-allowed rounded-[11px] bg-[var(--accent)] px-5.5 py-2.5 text-[13.5px] font-semibold text-white opacity-60"
          >
            Save commission rules
          </button>
        </div>

        <div className="rounded-2xl border border-[var(--border)] bg-[var(--surface)] p-5">
          <div className="mb-3.5 text-[13.5px] font-semibold text-[var(--text)]">
            Tiered fee schedule
          </div>
          <div className="flex flex-col">
            {SAMPLE_TIERS.map((t) => (
              <div
                key={t.range}
                className="flex items-center justify-between border-t border-[var(--border)] py-2.5 first:border-t-0"
              >
                <span className="text-[13px] text-[var(--text-2)]">{t.range}</span>
                <span className="font-mono text-[14px] font-semibold text-[var(--text)]">
                  {t.fee}
                </span>
              </div>
            ))}
          </div>
        </div>
      </div>

      <h2 className="mt-8 text-[16px] font-semibold text-[var(--text)]">Settlement history</h2>
      <div className="mt-3 overflow-hidden rounded-2xl border border-[var(--border)] bg-[var(--surface)]">
        {settlements.length === 0 && (
          <p className="px-5 py-6 text-[13px] text-[var(--text-3)]">
            No settlements yet — this fills in once your first billing period closes.
          </p>
        )}
        {settlements.map((r) => (
          <Link
            key={r.id}
            href={`/commission/${r.id}`}
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
            <div className="w-24 text-right">
              <div className="text-[11px] text-[var(--text-3)]">Net to master</div>
              <div className="mt-0.5 font-mono text-[13.5px] font-semibold text-[var(--text)]">
                ${r.netToMasterAmount.toFixed(2)}
              </div>
            </div>
          </Link>
        ))}
      </div>
    </div>
  );
}
