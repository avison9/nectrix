"use client";

import Link from "next/link";
import { useEffect, useState, useTransition } from "react";
import type { UserSummary } from "@nectrix/api-client";
import { searchUsersAction } from "./actions";
import { UserActions } from "./UserActions";

const DEBOUNCE_MS = 300;

/** TICKET-117 — the /users directory's search + results table, {@code isAdmin} gates the Suspend/Reinstate column. */
export function UserSearch({ isAdmin }: { isAdmin: boolean }) {
  const [query, setQuery] = useState("");
  const [results, setResults] = useState<UserSummary[]>([]);
  const [pending, startTransition] = useTransition();

  useEffect(() => {
    const timer = setTimeout(() => {
      startTransition(async () => {
        const found = await searchUsersAction(query);
        setResults(found);
      });
    }, DEBOUNCE_MS);
    return () => clearTimeout(timer);
  }, [query]);

  return (
    <div>
      <input
        value={query}
        onChange={(e) => setQuery(e.target.value)}
        placeholder="Search by email or name…"
        className="h-10 w-full max-w-md rounded-[10px] border border-[var(--border)] bg-transparent px-3.5 text-[13.5px] text-[var(--text)] outline-none focus:border-[var(--accent)]"
      />

      <div className="mt-4 overflow-hidden rounded-2xl border border-[var(--border)] bg-[var(--surface)]">
        <table className="w-full border-collapse text-[13px]">
          <thead>
            <tr className="border-b border-[var(--border)] text-left">
              {["Email", "Name", "Status", "2FA", ""].map((heading) => (
                <th
                  key={heading}
                  className="px-5 py-2.5 text-[11.5px] font-semibold tracking-wide text-[var(--text-3)] uppercase"
                >
                  {heading}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {!pending && results.length === 0 && (
              <tr>
                <td colSpan={5} className="px-5 py-8 text-center text-[var(--text-3)]">
                  No users match this search.
                </td>
              </tr>
            )}
            {results.map((user) => (
              <tr key={user.id} className="border-b border-[var(--border)] last:border-0">
                <td className="px-5 py-2.5">
                  <Link
                    href={`/users/${user.id}`}
                    className="font-medium text-[var(--text)] hover:text-[var(--accent)] hover:underline"
                  >
                    {user.email}
                  </Link>
                </td>
                <td className="px-5 py-2.5 text-[var(--text-2)]">{user.displayName}</td>
                <td className="px-5 py-2.5">
                  <span
                    className={`rounded-full px-2.5 py-1 text-[12px] font-semibold ${
                      user.status === "ACTIVE"
                        ? "bg-[var(--pos)]/15 text-[var(--pos)]"
                        : user.status === "DELETED"
                          ? "bg-[var(--surface-2)] text-[var(--text-3)]"
                          : "bg-[var(--neg)]/15 text-[var(--neg)]"
                    }`}
                  >
                    {user.status}
                  </span>
                </td>
                <td className="px-5 py-2.5 text-[var(--text-2)]">
                  {user.twoFactorEnabled ? "Enabled" : "—"}
                </td>
                <td className="px-5 py-2.5 text-right">
                  {isAdmin && (
                    <UserActions
                      user={user}
                      onUpdated={(updated) =>
                        setResults((prev) => prev.map((r) => (r.id === updated.id ? updated : r)))
                      }
                    />
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
