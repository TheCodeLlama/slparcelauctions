import type { Metadata } from "next";
import { BrowseShell } from "@/components/browse/BrowseShell";
import { searchAuctions } from "@/lib/api/auctions-search";
import { queryFromSearchParams } from "@/lib/search/url-codec";

export const metadata: Metadata = {
  title: "Browse Auctions · SLPA",
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
  const initialData = await searchAuctions(query);
  return (
    <BrowseShell
      initialQuery={query}
      initialData={initialData}
      title="Browse"
    />
  );
}
