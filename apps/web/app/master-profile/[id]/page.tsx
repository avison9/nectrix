import { getMasterProfile } from "@nectrix/api-client";
import { coreAppBaseUrl } from "@/lib/core-app";
import { requireSession } from "@/lib/auth";
import { fetchOrNotFound } from "@/lib/fetchOrNotFound";
import { EditMasterProfileForm } from "./EditMasterProfileForm";

export default async function MasterProfileDetailPage({
  params,
}: {
  params: Promise<{ id: string }>;
}) {
  const { accessToken } = await requireSession();
  const { id } = await params;
  const profile = await fetchOrNotFound(getMasterProfile(coreAppBaseUrl(), accessToken, id));

  return (
    <main className="mx-auto max-w-[480px] px-4 py-10">
      <h1 className="text-[20px] font-semibold tracking-tight text-[var(--text)]">
        Your Master profile
      </h1>
      <p className="mt-1.5 text-[13px] text-[var(--text-2)]">
        {profile.verifiedAt ? "Verified" : "Not yet verified"}
      </p>

      <EditMasterProfileForm profile={profile} />
    </main>
  );
}
