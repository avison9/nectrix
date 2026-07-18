"use client";

import { useState, useTransition } from "react";
import type { MasterProfile } from "@nectrix/api-client";
import { patchMasterProfileAction } from "./actions";

export function EditMasterProfileForm({ profile }: { profile: MasterProfile }) {
  const [displayName, setDisplayName] = useState(profile.displayName);
  const [bio, setBio] = useState(profile.bio ?? "");
  const [strategyTags, setStrategyTags] = useState(profile.strategyTags.join(", "));
  const [performanceFeePercent, setPerformanceFeePercent] = useState(
    String(profile.performanceFeePercent),
  );
  const [isPublic, setIsPublic] = useState(profile.isPublic);
  const [error, setError] = useState<string | undefined>();
  const [saved, setSaved] = useState(false);
  const [pending, startTransition] = useTransition();

  function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError(undefined);
    setSaved(false);
    startTransition(async () => {
      const result = await patchMasterProfileAction(profile.id, {
        displayName,
        bio: bio || undefined,
        strategyTags: strategyTags
          .split(",")
          .map((tag) => tag.trim())
          .filter(Boolean),
        performanceFeePercent: Number(performanceFeePercent),
        isPublic,
      });
      if ("error" in result) {
        setError(result.error);
        return;
      }
      setSaved(true);
    });
  }

  return (
    <form onSubmit={submit} className="mt-6 flex flex-col gap-3.5">
      <label className="flex flex-col gap-1.5">
        <span className="text-[12.5px] font-medium text-[var(--text-2)]">Display name</span>
        <input
          required
          value={displayName}
          onChange={(e) => setDisplayName(e.target.value)}
          className="h-10 rounded-[10px] border border-[var(--border)] bg-transparent px-3 text-[13.5px] text-[var(--text)] outline-none focus:border-[var(--accent)]"
        />
      </label>

      <label className="flex flex-col gap-1.5">
        <span className="text-[12.5px] font-medium text-[var(--text-2)]">Bio</span>
        <textarea
          value={bio}
          onChange={(e) => setBio(e.target.value)}
          rows={3}
          className="rounded-[10px] border border-[var(--border)] bg-transparent px-3 py-2 text-[13.5px] text-[var(--text)] outline-none focus:border-[var(--accent)]"
        />
      </label>

      <label className="flex flex-col gap-1.5">
        <span className="text-[12.5px] font-medium text-[var(--text-2)]">
          Strategy tags <span className="text-[var(--text-3)]">(comma-separated)</span>
        </span>
        <input
          value={strategyTags}
          onChange={(e) => setStrategyTags(e.target.value)}
          className="h-10 rounded-[10px] border border-[var(--border)] bg-transparent px-3 text-[13.5px] text-[var(--text)] outline-none focus:border-[var(--accent)]"
        />
      </label>

      <label className="flex flex-col gap-1.5">
        <span className="text-[12.5px] font-medium text-[var(--text-2)]">Performance fee %</span>
        <input
          type="number"
          min="0"
          max="100"
          step="0.01"
          required
          value={performanceFeePercent}
          onChange={(e) => setPerformanceFeePercent(e.target.value)}
          className="h-10 rounded-[10px] border border-[var(--border)] bg-transparent px-3 text-[13.5px] text-[var(--text)] outline-none focus:border-[var(--accent)]"
        />
      </label>

      <label className="flex items-center gap-2">
        <input type="checkbox" checked={isPublic} onChange={(e) => setIsPublic(e.target.checked)} />
        <span className="text-[12.5px] text-[var(--text-2)]">Listed publicly in the Masters directory</span>
      </label>

      {error && <p className="text-[12.5px] text-[var(--neg)]">{error}</p>}
      {saved && !error && <p className="text-[12.5px] text-[var(--pos)]">Saved.</p>}

      <button
        type="submit"
        disabled={pending}
        className="mt-1.5 h-[42px] rounded-[11px] bg-[var(--accent)] text-[13.5px] font-semibold text-white transition-opacity disabled:opacity-60"
      >
        {pending ? "Saving…" : "Save changes"}
      </button>
    </form>
  );
}
