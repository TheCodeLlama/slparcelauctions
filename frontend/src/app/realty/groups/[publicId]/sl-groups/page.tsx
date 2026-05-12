export const dynamic = "force-dynamic";

import { SlGroupsPage } from "@/components/realty/slgroup/SlGroupsPage";

interface PageProps {
  params: Promise<{ publicId: string }>;
}

/**
 * Realty Groups: E — SL group registration page. Entirely client-rendered
 * because the page-level state (registration table rows, pending-row
 * countdowns, mutation status) is per-user and depends on the live JWT,
 * which Server Components do not see.
 *
 * {@code export const dynamic = "force-dynamic"} prevents Amplify from
 * prerendering a stale snapshot at build time (see Frontend SSR caveats in
 * CLAUDE.md, and spec §6.6 — "Pending state changes per visit").
 */
export default async function Page({ params }: PageProps) {
  const { publicId } = await params;
  return (
    <div className="mx-auto max-w-4xl px-4 py-8 flex flex-col gap-6">
      <SlGroupsPage groupPublicId={publicId} />
    </div>
  );
}
