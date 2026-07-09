import { redirect } from "next/navigation";

// No dedicated landing page yet (System Health is still a stub, see
// TICKET-012's scope) — Audit Log is real/working, so it's the default view.
export default function Home() {
  redirect("/audit-log");
}
