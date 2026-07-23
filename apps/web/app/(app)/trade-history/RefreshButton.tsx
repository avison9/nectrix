"use client";

import { useRouter } from "next/navigation";
import { useState, useTransition } from "react";

/**
 * Bugfix — trade-history/page.tsx is a plain Server Component (GET-form filters, no client JS) so
 * there was no way to re-fetch the current page's data short of a full browser reload.
 * router.refresh() re-runs the Server Component with the exact same searchParams, which a plain
 * `<Link>` back to the same URL would NOT do (Next.js skips a re-fetch when the URL is unchanged).
 */
export function RefreshButton() {
  const router = useRouter();
  const [pending, startTransition] = useTransition();
  const [justRefreshed, setJustRefreshed] = useState(false);

  function refresh() {
    startTransition(() => {
      router.refresh();
    });
    setJustRefreshed(true);
    setTimeout(() => setJustRefreshed(false), 1200);
  }

  return (
    <button
      type="button"
      onClick={refresh}
      disabled={pending}
      className="h-9 rounded-[9px] border border-[var(--border)] px-3.5 text-[12.5px] font-semibold text-[var(--text)] transition-colors hover:bg-[var(--surface-2)] disabled:opacity-60"
    >
      {pending ? "Refreshing…" : justRefreshed ? "Refreshed" : "Refresh"}
    </button>
  );
}
