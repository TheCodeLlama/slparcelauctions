import type { Metadata } from "next";
import { BrowseShell } from "@/components/browse/BrowseShell";
import { resolveBrowseInitialData } from "@/lib/api/auctions-search";
import { queryFromSearchParams } from "@/lib/search/url-codec";

export const metadata: Metadata = {
  title: "Browse Auctions · SLParcels",
  description: "Discover active land auctions across the grid.",
};

type SP = Record<string, string | string[] | undefined>;

function toSearchParams(params: SP): URLSearchParams {
  const sp = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) {
    if (typeof v === "string") sp.set(k, v);
    else if (Array.isArray(v) && typeof v[0] === "string") sp.set(k, v[0]);
  }
  return sp;
}

export default async function BrowsePage({
  searchParams,
}: {
  searchParams: Promise<SP>;
}) {
  const params = await searchParams;
  const sp = toSearchParams(params);
  const query = queryFromSearchParams(sp);
  // 4xx filter errors (e.g. an unknown near_region) resolve to an empty
  // result set + an inline error code instead of crashing the route; 5xx
  // and network failures rethrow into browse/error.tsx.
  const { data: initialData, errorCode } =
    await resolveBrowseInitialData(query);
  return (
    <BrowseShell
      initialQuery={query}
      initialData={initialData}
      initialErrorCode={errorCode}
      title="Browse"
    />
  );
}
