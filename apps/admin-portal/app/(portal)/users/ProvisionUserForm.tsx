"use client";

import { useActionState } from "react";
import { provisionUserAction, type ProvisionUserActionState } from "./actions";

const initialState: ProvisionUserActionState = {};

export function ProvisionUserForm() {
  const [state, formAction, pending] = useActionState(provisionUserAction, initialState);

  return (
    <div className="rounded-2xl border border-[var(--border)] bg-[var(--surface)] p-5">
      <div className="mb-4 text-[14px] font-semibold text-[var(--text)]">
        Provision a new account
      </div>
      <form action={formAction} className="flex flex-wrap items-end gap-3">
        <label className="flex min-w-[200px] flex-1 flex-col gap-1.5">
          <span className="text-[12.5px] font-medium text-[var(--text-2)]">Email</span>
          <input
            name="email"
            type="email"
            required
            className="h-10 rounded-[10px] border border-[var(--border)] bg-transparent px-3 text-[13.5px] text-[var(--text)] outline-none focus:border-[var(--accent)]"
          />
        </label>
        <label className="flex min-w-[160px] flex-1 flex-col gap-1.5">
          <span className="text-[12.5px] font-medium text-[var(--text-2)]">Display name</span>
          <input
            name="displayName"
            type="text"
            required
            className="h-10 rounded-[10px] border border-[var(--border)] bg-transparent px-3 text-[13.5px] text-[var(--text)] outline-none focus:border-[var(--accent)]"
          />
        </label>
        <label className="flex min-w-[160px] flex-col gap-1.5">
          <span className="text-[12.5px] font-medium text-[var(--text-2)]">Temporary password</span>
          <input
            name="password"
            type="password"
            required
            minLength={12}
            className="h-10 rounded-[10px] border border-[var(--border)] bg-transparent px-3 text-[13.5px] text-[var(--text)] outline-none focus:border-[var(--accent)]"
          />
        </label>
        <label className="flex w-[140px] flex-col gap-1.5">
          <span className="text-[12.5px] font-medium text-[var(--text-2)]">Role</span>
          <select
            name="role"
            defaultValue="SUPPORT"
            className="h-10 rounded-[10px] border border-[var(--border)] bg-transparent px-3 text-[13.5px] text-[var(--text)] outline-none focus:border-[var(--accent)]"
          >
            <option value="SUPPORT">Support</option>
            <option value="ADMIN">Admin</option>
            <option value="MASTER">Master</option>
            <option value="FOLLOWER">Follower</option>
          </select>
        </label>
        <button
          type="submit"
          disabled={pending}
          className="h-10 shrink-0 rounded-[11px] bg-[var(--accent)] px-5 text-[13.5px] font-semibold text-white transition-opacity disabled:opacity-60"
        >
          {pending ? "Provisioning…" : "Provision account"}
        </button>
      </form>

      {state.error && <p className="mt-3 text-[12.5px] text-[var(--neg)]">{state.error}</p>}
      {state.success && <p className="mt-3 text-[12.5px] text-[var(--pos)]">{state.success}</p>}
    </div>
  );
}
