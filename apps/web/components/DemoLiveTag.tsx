/**
 * TICKET-110 AC5 — demo/live accounts clearly distinguished throughout the UI
 * (FR-2.4: never let a demo/live mismatch happen implicitly). Purely
 * presentational; backed by BrokerAccountSummary.isDemo.
 */
export function DemoLiveTag({ isDemo }: { isDemo: boolean }) {
  return (
    <span
      className={
        "inline-flex items-center rounded-full px-2 py-0.5 text-[11px] font-semibold tracking-wide uppercase " +
        (isDemo
          ? "bg-[var(--accent-2)] text-[var(--text-2)]"
          : "bg-[var(--neg)]/15 text-[var(--neg)]")
      }
    >
      {isDemo ? "Demo" : "Live"}
    </span>
  );
}
