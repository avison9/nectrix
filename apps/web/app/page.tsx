import { redirect } from "next/navigation";

// proxy.ts already gates auth on every route except /login -- an unauthenticated
// visitor lands on /login before this ever renders.
export default function Home() {
  redirect("/broker-accounts");
}
