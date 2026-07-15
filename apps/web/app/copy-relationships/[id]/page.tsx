import { getCopyRelationship, listCopyRelationshipTrades } from "@nectrix/api-client";
import { coreAppBaseUrl } from "@/lib/core-app";
import { requireSession } from "@/lib/auth";
import { fetchOrNotFound } from "@/lib/fetchOrNotFound";
import { CopyRelationshipStatusBadge } from "@/components/CopyRelationshipStatusBadge";
import { CopyRelationshipActions } from "./CopyRelationshipActions";
import { TradesHistory } from "./TradesHistory";

export default async function CopyRelationshipDetailPage({
  params,
}: {
  params: Promise<{ id: string }>;
}) {
  const { accessToken } = await requireSession();
  const { id } = await params;
  const relationship = await fetchOrNotFound(getCopyRelationship(coreAppBaseUrl(), accessToken, id));
  const tradesPage = await listCopyRelationshipTrades(coreAppBaseUrl(), accessToken, id);

  return (
    <main className="mx-auto max-w-[560px] px-4 py-10">
      <div className="flex items-center justify-between">
        <h1 className="text-[20px] font-semibold tracking-tight text-[var(--text)]">
          Copy relationship
        </h1>
        <CopyRelationshipStatusBadge status={relationship.status} />
      </div>
      <p className="mt-1.5 font-mono text-[12.5px] text-[var(--text-2)]">{relationship.id}</p>

      <CopyRelationshipActions relationship={relationship} />

      <div className="mt-8">
        <h2 className="text-[15px] font-semibold text-[var(--text)]">Copy settings</h2>
        <p className="mt-1 text-[12.5px] text-[var(--text-3)]">
          Read-only for now — editing requires a money-management/risk-profile management API that
          hasn&apos;t shipped yet (TICKET-104/105&apos;s own scope, not this ticket&apos;s).
        </p>
        <div className="mt-3 rounded-[12px] border border-[var(--border)] bg-[var(--surface)] px-4">
          <Row label="Copy mode" value={relationship.moneyManagementProfile.method} />
          {relationship.moneyManagementProfile.multiplier !== null && (
            <Row
              label="Lot multiplier"
              value={`${relationship.moneyManagementProfile.multiplier}×`}
              mono
            />
          )}
          {relationship.moneyManagementProfile.fixedLotSize !== null && (
            <Row
              label="Fixed lot size"
              value={String(relationship.moneyManagementProfile.fixedLotSize)}
              mono
            />
          )}
          {relationship.riskProfile.maxLotPerTrade !== null && (
            <Row label="Max lot per trade" value={String(relationship.riskProfile.maxLotPerTrade)} mono />
          )}
          {relationship.riskProfile.drawdownPausePct !== null && (
            <Row
              label="Drawdown pause"
              value={`${relationship.riskProfile.drawdownPausePct}%`}
              mono
            />
          )}
          {relationship.riskProfile.drawdownCloseAllPct !== null && (
            <Row
              label="Drawdown stop (force-close)"
              value={`${relationship.riskProfile.drawdownCloseAllPct}%`}
              mono
              last
            />
          )}
        </div>
      </div>

      <TradesHistory relationshipId={id} initialPage={tradesPage} />
    </main>
  );
}

function Row({
  label,
  value,
  mono,
  last,
}: {
  label: string;
  value: string;
  mono?: boolean;
  last?: boolean;
}) {
  return (
    <div
      className={`flex items-center justify-between py-3 ${last ? "" : "border-b border-[var(--border)]"}`}
    >
      <span className="text-[13px] font-medium text-[var(--text)]">{label}</span>
      <span
        className={`text-[13px] text-[var(--text-2)] ${mono ? "font-mono text-[var(--accent)]" : ""}`}
      >
        {value}
      </span>
    </div>
  );
}
