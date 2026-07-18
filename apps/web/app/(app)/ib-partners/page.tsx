import { requireSession } from "@/lib/auth";

const SAMPLE_BROKERS = [
  { initial: "IC", name: "IC Markets", region: "Global", followers: "64", rebate: "$8.20 / lot" },
  { initial: "VT", name: "Vantage", region: "Global", followers: "41", rebate: "$6.50 / lot" },
  { initial: "PU", name: "Pepperstone", region: "EU / AU", followers: "23", rebate: "$5.00 / lot" },
];

/**
 * Mirrors Nectrix.dc.html's `MASTER · IB PARTNERS` (`vMasterIb`, `:678-717`). TICKET-119 (broker IB
 * links) hasn't been built anywhere yet — same "rendered but inert" precedent
 * app/(app)/master/followers/[id]/page.tsx already established: mock's own sample rows, no fetch
 * calls, primary actions disabled with a tooltip.
 */
export default async function IbPartnersPage() {
  await requireSession();

  return (
    <div className="mx-auto max-w-[1080px]">
      <div className="mb-6">
        <h1 className="text-[25px] font-semibold tracking-tight text-[var(--text)]">
          IB partner links
        </h1>
        <p className="mt-1.5 text-[14px] text-[var(--text-2)]">
          Set your Introducing Broker link for each broker. New followers open accounts under these
          links so you earn broker rebates.
        </p>
      </div>

      <p
        className="mb-4 text-[12.5px] text-[var(--text-3)]"
        title="Broker IB links aren't available yet — TICKET-119 hasn't shipped"
      >
        Showing sample brokers — this page isn&apos;t wired up to real IB link data yet.
      </p>

      <div className="grid grid-cols-1 gap-4 opacity-70 sm:grid-cols-2 lg:grid-cols-3">
        {SAMPLE_BROKERS.map((b) => (
          <div
            key={b.name}
            className="flex flex-col gap-4 rounded-2xl border border-[var(--border)] bg-[var(--surface)] p-5"
          >
            <div className="flex items-center gap-3">
              <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-[11px] border border-[var(--border)] bg-[var(--surface-2)] text-[15px] font-bold text-[var(--text)]">
                {b.initial}
              </div>
              <div className="min-w-0 flex-1">
                <div className="text-[15px] font-semibold text-[var(--text)]">{b.name}</div>
                <div className="text-[12px] text-[var(--text-3)]">{b.region}</div>
              </div>
            </div>
            <div>
              <div className="mb-1.5 text-[11.5px] font-medium text-[var(--text-2)]">
                IB / affiliate link
              </div>
              <div className="flex gap-2">
                <input
                  disabled
                  placeholder="Paste your IB link…"
                  className="h-[38px] min-w-0 flex-1 rounded-[9px] border border-[var(--border)] bg-[var(--surface-2)] px-3 font-mono text-[12px] text-[var(--text)] outline-none"
                />
                <button
                  type="button"
                  disabled
                  title="Broker IB links aren't available yet — TICKET-119 hasn't shipped"
                  className="h-[38px] shrink-0 cursor-not-allowed rounded-[9px] bg-[var(--accent)] px-3.5 text-[12.5px] font-semibold text-white opacity-60"
                >
                  Save
                </button>
              </div>
            </div>
            <div className="flex gap-5 border-t border-[var(--border)] pt-3.5">
              <div>
                <div className="text-[11px] text-[var(--text-3)]">Followers</div>
                <div className="mt-0.5 font-mono text-[16px] font-semibold text-[var(--text)]">
                  {b.followers}
                </div>
              </div>
              <div>
                <div className="text-[11px] text-[var(--text-3)]">Rebate</div>
                <div className="mt-0.5 font-mono text-[16px] font-semibold text-[var(--text)]">
                  {b.rebate}
                </div>
              </div>
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}
