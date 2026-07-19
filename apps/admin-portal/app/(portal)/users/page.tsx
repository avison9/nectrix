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
    <div className="max-w-3xl">
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
