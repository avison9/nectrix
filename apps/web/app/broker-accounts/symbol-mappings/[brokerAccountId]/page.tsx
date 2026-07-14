import { listSymbolMappings } from "@nectrix/api-client";
import { coreAppBaseUrl } from "@/lib/core-app";
import { requireSession } from "@/lib/auth";
import { SymbolMappingsClient } from "./SymbolMappingsClient";

/**
 * TICKET-103's symbol-mapping confirmation flow, surfaced here per docs/07
 * §7.5's flowchart: "Fetch/derive SymbolSpec set, populate symbol_mappings" ->
 * user confirms each row before continuing to role selection.
 */
export default async function SymbolMappingsPage({
  params,
}: {
  params: Promise<{ brokerAccountId: string }>;
}) {
  const { accessToken } = await requireSession();
  const { brokerAccountId } = await params;
  const mappings = await listSymbolMappings(coreAppBaseUrl(), accessToken, brokerAccountId);

  return (
    <main className="mx-auto max-w-[560px] px-4 py-10">
      <h1 className="text-[20px] font-semibold tracking-tight text-[var(--text)]">
        Confirm symbol mappings
      </h1>
      <p className="mt-1.5 text-[13px] text-[var(--text-2)]">
        Review the auto-suggested broker symbol names, correcting any that don&apos;t match.
      </p>

      <SymbolMappingsClient brokerAccountId={brokerAccountId} initialMappings={mappings} />
    </main>
  );
}
