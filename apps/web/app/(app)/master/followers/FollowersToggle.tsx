"use client";

import { useState, type ReactNode } from "react";

/**
 * Switches between the invite pipeline and the real followers list — same client-side show/hide
 * pattern follower/referrals/ReferralsToggle.tsx already establishes (both pre-rendered
 * server-side, passed in as props — toggling never re-fetches).
 */
export function FollowersToggle({
  pipelineContent,
  followersContent,
}: {
  pipelineContent: ReactNode;
  followersContent: ReactNode;
}) {
  const [tab, setTab] = useState<"pipeline" | "followers">("pipeline");

  return (
    <div>
      <div className="mb-4 inline-flex rounded-[11px] border border-[var(--border)] bg-[var(--surface)] p-1">
        <button
          type="button"
          onClick={() => setTab("pipeline")}
          className={`rounded-[8px] px-4 py-1.5 text-[13px] font-semibold transition-colors ${
            tab === "pipeline"
              ? "bg-[var(--accent)] text-white"
              : "text-[var(--text-2)] hover:text-[var(--text)]"
          }`}
        >
          Invite pipeline
        </button>
        <button
          type="button"
          onClick={() => setTab("followers")}
          className={`rounded-[8px] px-4 py-1.5 text-[13px] font-semibold transition-colors ${
            tab === "followers"
              ? "bg-[var(--accent)] text-white"
              : "text-[var(--text-2)] hover:text-[var(--text)]"
          }`}
        >
          Followers
        </button>
      </div>
      {tab === "pipeline" ? pipelineContent : followersContent}
    </div>
  );
}
