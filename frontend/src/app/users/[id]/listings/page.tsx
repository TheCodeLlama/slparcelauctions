import type { Metadata } from "next";
import { notFound } from "next/navigation";
import { BrowseShell } from "@/components/browse/BrowseShell";
import { SellerHeader } from "@/components/browse/SellerHeader";
import { searchAuctions } from "@/lib/api/auctions-search";
import { isApiError } from "@/lib/api";
import { userApi } from "@/lib/user/api";
import { queryFromSearchParams } from "@/lib/search/url-codec";

type SP = Record<string, string | string[] | undefined>;

function toSearchParams(params: SP): URLSearchParams {
  const sp = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) {
    if (typeof v === "string") sp.set(k, v);
    else if (Array.isArray(v) && typeof v[0] === "string") sp.set(k, v[0]);
  }
  return sp;
}

export const metadata: Metadata = {
  title: "Seller listings · SLPA",
};

export default async function SellerListingsPage({
  params,
  searchParams,
}: {
  params: Promise<{ id: string }>;
  searchParams: Promise<SP>;
}) {
  const { id } = await params;
  const sellerId = Number(id);
  if (!Number.isInteger(sellerId) || sellerId <= 0) notFound();

  const spData = await searchParams;
  const urlQuery = queryFromSearchParams(toSearchParams(spData));
  const query = { ...urlQuery, sellerId };

  let user;
  try {
    user = await userApi.publicProfile(sellerId);
  } catch (e) {
    if (isApiError(e) && e.status === 404) notFound();
    throw e;
  }

  const initialData = await searchAuctions(query);

  return (
    <div className="flex flex-col">
      <SellerHeader user={user} />
      <BrowseShell
        initialQuery={query}
        initialData={initialData}
        fixedFilters={{ sellerId }}
        hiddenFilterGroups={["distance"]}
        title={`${user.displayName ?? "Seller"}'s listings`}
      />
    </div>
  );
}
