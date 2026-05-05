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
  title: "Seller listings · SLParcels",
};

export default async function SellerListingsPage({
  params,
  searchParams,
}: {
  params: Promise<{ publicId: string }>;
  searchParams: Promise<SP>;
}) {
  const { publicId } = await params;
  if (!publicId) notFound();
  const sellerPublicId = publicId;

  const spData = await searchParams;
  const urlQuery = queryFromSearchParams(toSearchParams(spData));
  const query = { ...urlQuery, sellerPublicId };

  // Both calls are independent — parallelize them. A 404 on the profile
  // fetch means "no such seller" and must trigger notFound(); a 404 on
  // the search response is not currently expected, but any ApiError
  // from the search will still propagate.
  let user;
  let initialData;
  try {
    [user, initialData] = await Promise.all([
      userApi.publicProfile(sellerPublicId),
      searchAuctions(query),
    ]);
  } catch (e) {
    if (isApiError(e) && e.status === 404) notFound();
    throw e;
  }

  return (
    <div className="flex flex-col">
      <SellerHeader user={user} />
      <BrowseShell
        initialQuery={query}
        initialData={initialData}
        fixedFilters={{ sellerPublicId }}
        hiddenFilterGroups={["distance"]}
        title={`${user.displayName ?? "Seller"}'s listings`}
      />
    </div>
  );
}
