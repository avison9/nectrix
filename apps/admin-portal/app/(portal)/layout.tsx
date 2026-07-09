import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import { Sidebar } from "@/components/Sidebar";
import { Topbar } from "@/components/Topbar";
import { verifyAccessToken, hasPortalRole } from "@/lib/session";

// middleware.ts already rejects a request with no valid portal session before
// it reaches here — this re-verification is what supplies the session data
// (email/roles) the shell itself renders, and is a second, independent check
// rather than trusting a header middleware could have set.
export default async function PortalLayout({ children }: { children: React.ReactNode }) {
  const jar = await cookies();
  const token = jar.get("access_token")?.value;
  const session = token ? await verifyAccessToken(token) : null;

  if (!hasPortalRole(session)) {
    redirect("/login");
  }

  return (
    <div className="flex min-h-screen">
      <Sidebar />
      <div className="flex min-w-0 flex-1 flex-col">
        <Topbar email={session!.email} roles={session!.roles} />
        <main className="flex-1 overflow-y-auto p-6">{children}</main>
      </div>
    </div>
  );
}
