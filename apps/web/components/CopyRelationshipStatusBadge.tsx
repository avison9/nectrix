import type { CopyRelationshipStatus } from "@nectrix/domain-model";

const LABEL: Record<CopyRelationshipStatus, string> = {
  PENDING_RISK_ACK: "Risk ack required",
  PENDING_AGREEMENT: "Agreement required",
  ACTIVE: "Active",
  PAUSED: "Paused",
  STOPPED: "Stopped",
};

const COLOR: Record<CopyRelationshipStatus, string> = {
  PENDING_RISK_ACK: "bg-amber-500/15 text-amber-600",
  PENDING_AGREEMENT: "bg-amber-500/15 text-amber-600",
  ACTIVE: "bg-[var(--pos)]/15 text-[var(--pos)]",
  PAUSED: "bg-[var(--accent-2)] text-[var(--text-2)]",
  STOPPED: "bg-[var(--neg)]/15 text-[var(--neg)]",
};

export function CopyRelationshipStatusBadge({ status }: { status: CopyRelationshipStatus }) {
  return (
    <span
      className={`inline-flex items-center rounded-full px-2 py-0.5 text-[11px] font-semibold ${COLOR[status]}`}
    >
      {LABEL[status]}
    </span>
  );
}
