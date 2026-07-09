export function StubPage({ title, description }: { title: string; description: string }) {
  return (
    <div className="max-w-2xl">
      <h1 className="text-[25px] font-semibold tracking-tight text-[var(--text)]">{title}</h1>
      <p className="mt-1.5 text-[14px] text-[var(--text-2)]">{description}</p>
      <div className="mt-6 rounded-2xl border border-dashed border-[var(--border)] bg-[var(--surface)] px-6 py-10 text-center">
        <p className="text-[13.5px] font-medium text-[var(--text-2)]">Coming soon</p>
        <p className="mt-1 text-[12.5px] text-[var(--text-3)]">
          This section is a placeholder — see the ticket roadmap for when it lands.
        </p>
      </div>
    </div>
  );
}
