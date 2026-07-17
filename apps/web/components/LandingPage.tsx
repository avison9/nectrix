import Link from "next/link";
import { PLANS } from "@/lib/plans";

// TICKET-114 — mirrors Nectrix.dc.html's `PUBLIC · LANDING PAGE` section (hero/about/product/
// pricing/footer), translated into this app's real Tailwind + CSS-var convention (same
// var(--...) tokens app/globals.css defines from the mock's own LIGHT/DARK theme objects, same
// approach app/masters/page.tsx already established for TICKET-112's public discovery pages) —
// not a copy of the mock's raw inline-style markup. productCards/pricingPlans are templated
// loops in the mock with no literal content; copy below is this ticket's own, plans come from
// the shared lib/plans.ts catalog (also used by the registration page's plan picker).
const PRODUCT_CARDS = [
  {
    title: "Run your own strategy",
    body: "Get invited as a Master by an admin, build a track record, and let others copy your trades in real time.",
  },
  {
    title: "Follow a master you trust",
    body: "Accept an invite from a master and mirror their trades automatically, with full transparency on fees and risk.",
  },
  {
    title: "Trade solo, on your own accounts",
    body: "Register yourself, link a main account and one or more of your own accounts to copy to — no invite required.",
  },
];

export function LandingPage() {
  return (
    <div>
      <header className="sticky top-0 z-20 flex h-[68px] items-center gap-5 border-b border-[var(--border)] bg-[var(--surface)] px-7">
        <div className="text-[17px] font-semibold tracking-tight text-[var(--text)]">Nectrix</div>
        <nav className="ml-2 flex gap-1.5">
          <Link
            href="/masters"
            className="rounded-lg px-3 py-2 text-[13.5px] font-medium text-[var(--text-2)] hover:text-[var(--text)]"
          >
            Masters
          </Link>
          <a
            href="#about"
            className="rounded-lg px-3 py-2 text-[13.5px] font-medium text-[var(--text-2)] hover:text-[var(--text)]"
          >
            About
          </a>
          <a
            href="#product"
            className="rounded-lg px-3 py-2 text-[13.5px] font-medium text-[var(--text-2)] hover:text-[var(--text)]"
          >
            Product
          </a>
          <a
            href="#pricing"
            className="rounded-lg px-3 py-2 text-[13.5px] font-medium text-[var(--text-2)] hover:text-[var(--text)]"
          >
            Pricing
          </a>
        </nav>
        <div className="flex-1" />
        <Link
          href="/login"
          className="flex h-[38px] items-center rounded-[10px] border border-[var(--border)] px-4 text-[13.5px] font-semibold text-[var(--text)] hover:bg-[var(--surface-2)]"
        >
          Log in
        </Link>
        <Link
          href="/register"
          className="flex h-[38px] items-center rounded-[10px] bg-[var(--accent)] px-[18px] text-[13.5px] font-semibold text-white hover:opacity-90"
        >
          Get started
        </Link>
      </header>

      <section className="flex flex-col items-center px-7 pb-19 pt-22 text-center">
        <div className="mb-5 inline-flex items-center gap-1.5 rounded-full bg-[var(--accent-2)] px-3.5 py-1.5">
          <span className="h-1.5 w-1.5 rounded-full bg-[var(--accent)]" />
          <span className="text-xs font-semibold tracking-wide text-[var(--accent)]">
            Multi-master copy trading
          </span>
        </div>
        <h1 className="max-w-[760px] text-[52px] font-semibold leading-[1.08] tracking-tight text-[var(--text)]">
          Copy trading, built for both sides of the trade.
        </h1>
        <p className="mt-5 max-w-[560px] text-[16.5px] leading-[1.55] text-[var(--text-2)]">
          Nectrix connects strategy providers with followers in real time — or lets you run both
          roles yourself, under one account.
        </p>
        <div className="mt-8 flex flex-wrap justify-center gap-3">
          <Link
            href="/register"
            className="flex h-12 items-center rounded-xl bg-[var(--accent)] px-6 text-[14.5px] font-semibold text-white transition-transform hover:-translate-y-px hover:opacity-95"
          >
            Start copying for yourself
          </Link>
          <Link
            href="/masters"
            className="flex h-12 items-center rounded-xl border border-[var(--border)] bg-[var(--surface)] px-6 text-[14.5px] font-semibold text-[var(--text)] hover:bg-[var(--surface-2)]"
          >
            Request invite
          </Link>
        </div>
        <p className="mt-4.5 text-[12.5px] text-[var(--text-3)]">
          Invites are unique links sent by a master — no invite needed to trade solo.
        </p>
        <Link
          href="/masters"
          className="mt-2.5 text-[12.5px] font-semibold text-[var(--accent)] hover:opacity-80"
        >
          Browse masters &amp; their track record →
        </Link>
      </section>

      <section
        id="about"
        className="border-y border-[var(--border)] bg-[var(--surface)] px-7 py-16"
      >
        <div className="mx-auto grid max-w-[920px] grid-cols-[repeat(auto-fit,minmax(260px,1fr))] items-start gap-10">
          <div>
            <div className="mb-2.5 text-xs font-semibold uppercase tracking-wider text-[var(--accent)]">
              About us
            </div>
            <h2 className="text-[28px] font-semibold leading-[1.25] tracking-tight text-[var(--text)]">
              Built by traders who were tired of spreadsheets and Telegram signals.
            </h2>
          </div>
          <p className="text-[14.5px] leading-[1.7] text-[var(--text-2)]">
            Nectrix mirrors trades from a master account to any number of follower accounts in
            milliseconds, with full transparency on performance, fees and risk. Whether you&apos;re
            building a following as a strategy provider, copying a trader you trust, or running the
            whole thing solo, everything settles through one auditable ledger.
          </p>
        </div>
      </section>

      <section id="product" className="px-7 py-18">
        <div className="mx-auto max-w-[1080px]">
          <div className="mb-10 text-center">
            <div className="mb-2.5 text-xs font-semibold uppercase tracking-wider text-[var(--accent)]">
              Product
            </div>
            <h2 className="text-[30px] font-semibold tracking-tight text-[var(--text)]">
              Three ways to use Nectrix
            </h2>
          </div>
          <div className="grid grid-cols-[repeat(auto-fit,minmax(260px,1fr))] gap-4.5">
            {PRODUCT_CARDS.map((pc) => (
              <div
                key={pc.title}
                className="flex flex-col gap-3 rounded-[18px] border border-[var(--border)] bg-[var(--surface)] p-6"
              >
                <div className="text-[16.5px] font-semibold text-[var(--text)]">{pc.title}</div>
                <div className="text-[13.5px] leading-[1.6] text-[var(--text-2)]">{pc.body}</div>
              </div>
            ))}
          </div>
        </div>
      </section>

      <section
        id="pricing"
        className="border-t border-[var(--border)] bg-[var(--surface)] px-7 py-18 pb-22"
      >
        <div className="mx-auto max-w-[1080px]">
          <div className="mb-10 text-center">
            <div className="mb-2.5 text-xs font-semibold uppercase tracking-wider text-[var(--accent)]">
              Pricing
            </div>
            <h2 className="text-[30px] font-semibold tracking-tight text-[var(--text)]">
              Simple pricing for every role
            </h2>
            <p className="mx-auto mt-2 max-w-[520px] text-[13.5px] text-[var(--text-2)]">
              These plans are for trading solo on your own accounts — Master and Follower access is
              always invite-based, never something you buy.
            </p>
          </div>
          <div className="grid grid-cols-[repeat(auto-fit,minmax(270px,1fr))] items-stretch gap-4.5">
            {PLANS.map((pl) => (
              <div
                key={pl.code}
                className={`flex flex-col rounded-2xl p-6 ${
                  pl.featured
                    ? "bg-[var(--accent)] text-white"
                    : "border border-[var(--border)] bg-[var(--bg)] text-[var(--text)]"
                }`}
              >
                {pl.featured && (
                  <div className="mb-1.5 w-fit rounded-full bg-white/16 px-2.5 py-0.5 text-[11px] font-semibold text-white">
                    Most flexible
                  </div>
                )}
                <div className={`text-base font-semibold ${pl.featured ? "text-white" : "text-[var(--text)]"}`}>
                  {pl.name}
                </div>
                <div className="mt-1.5 flex items-baseline gap-1">
                  <span className="text-[32px] font-semibold tracking-tight">{pl.price}</span>
                  <span
                    className={`text-[13px] ${pl.featured ? "text-white/70" : "text-[var(--text-3)]"}`}
                  >
                    {pl.per}
                  </span>
                </div>
                <div className={`mt-1 text-[13px] ${pl.featured ? "text-white/80" : "text-[var(--text-2)]"}`}>
                  {pl.desc}
                </div>
                <div className="my-4.5 flex flex-1 flex-col gap-2">
                  {pl.features.map((f) => (
                    <div key={f} className="flex items-center gap-2 text-[13px]">
                      <span>✓</span>
                      <span>{f}</span>
                    </div>
                  ))}
                </div>
                <Link
                  href={`/register?plan=${pl.code}`}
                  className={`rounded-xl py-2.5 text-center text-[13.5px] font-semibold ${
                    pl.featured
                      ? "bg-white text-[var(--accent)] hover:opacity-90"
                      : "border border-[var(--border)] text-[var(--text)] hover:bg-[var(--surface-2)]"
                  }`}
                >
                  Get started
                </Link>
              </div>
            ))}
          </div>
        </div>
      </section>

      <footer className="flex flex-wrap items-center justify-between gap-2.5 px-7 py-6.5">
        <span className="text-[13px] font-semibold text-[var(--text)]">Nectrix</span>
        <span className="text-xs text-[var(--text-3)]">© 2026 Nectrix. All rights reserved.</span>
      </footer>
    </div>
  );
}
