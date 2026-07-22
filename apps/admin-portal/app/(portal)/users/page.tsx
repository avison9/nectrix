import { cookies } from "next/headers";
import { verifyAccessToken } from "@/lib/session";
import { ProvisionUserForm } from "./ProvisionUserForm";
import { UserSearch } from "./UserSearch";

// TICKET-117 — extends TICKET-012's provisioning-only page with a real search + suspend/
// reinstate directory (previously a "coming soon" placeholder).
export default async function UsersPage() {
  const jar = await cookies();
  const token = jar.get("access_token")?.value;
  const session = token ? await verifyAccessToken(token) : null;
  const isAdmin = !!session?.roles.includes("ADMIN");

  return (
    // Bugfix — was max-w-3xl, a cap too narrow to fit the Suspend/Reinstate + Delete actions
    // column (the rounded-2xl table wrapper below uses overflow-hidden for its corner-rounding,
    // which also silently clips anything past the cap, with no scrollbar to reach it). max-w-5xl
    // matches this same codebase's own established pattern for other wide, data-heavy admin
    // pages (disputes/page.tsx uses max-w-4xl for its own narrower table).
    <div className="max-w-5xl">
      <h1 className="text-[25px] font-semibold tracking-tight text-[var(--text)]">Users</h1>
      <p className="mt-1.5 text-[14px] text-[var(--text-2)]">
        Provision platform Admin/Support accounts — the only way one comes into existence, since
        there is no self-registration anywhere on Nectrix.
      </p>

      <div className="mt-6">
        <ProvisionUserForm />
      </div>

      <h2 className="mt-8 text-[16px] font-semibold text-[var(--text)]">All users</h2>
      <div className="mt-3">
        <UserSearch isAdmin={isAdmin} />
      </div>
    </div>
  );
}
