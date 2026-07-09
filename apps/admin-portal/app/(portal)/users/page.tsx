import { ProvisionUserForm } from "./ProvisionUserForm";

export default function UsersPage() {
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

      <div className="mt-6 rounded-2xl border border-dashed border-[var(--border)] bg-[var(--surface)] px-6 py-10 text-center">
        <p className="text-[13.5px] font-medium text-[var(--text-2)]">
          Search, view, and suspend/impersonate — coming soon
        </p>
        <p className="mt-1 text-[12.5px] text-[var(--text-3)]">
          The full user directory and account actions are Phase 1 work.
        </p>
      </div>
    </div>
  );
}
