import { Hero } from "@/components/marketing/Hero";
import { FeaturedRow } from "@/components/marketing/FeaturedRow";
import { TrustStrip } from "@/components/marketing/TrustStrip";
import { fetchFeatured } from "@/lib/api/auctions-search";

/**
 * Homepage server component. Fans out the three featured-rail fetches under
 * a single Promise.allSettled so one endpoint's 5xx doesn't cascade into a
 * full-page 500 — failing rails render a neutral placeholder instead.
 *
 * NB: Promise.allSettled (not Promise.all) is load-bearing here. Promise.all
 * short-circuits on the first rejection, which would couple the three
 * independent rails; allSettled never rejects, so every rail lands in the
 * render regardless of the others' outcomes.
 *
 * StatsBar is intentionally omitted. Per product decision, launching with
 * low activity numbers reads as a liability rather than social proof — the
 * backend /stats/public endpoint is live but the component is deferred until
 * activity threshold is met. See docs/implementation/DEFERRED_WORK.md.
 */
export default async function HomePage() {
  const [endingSoon, justListed, mostActive] = await Promise.allSettled([
    fetchFeatured("ending-soon"),
    fetchFeatured("just-listed"),
    fetchFeatured("most-active"),
  ]);

  // The Hero stack uses the first 3 ending-soon listings as its featured
  // preview. If the fetch failed, it falls back to an empty stack.
  const heroFeatured =
    endingSoon.status === "fulfilled" ? endingSoon.value.content.slice(0, 3) : [];

  return (
    <>
      <Hero featured={heroFeatured} />
      <FeaturedRow
        title="Ending soon"
        sub="Auctions closing in the next few hours."
        sortLink="/browse?sort=ending_soonest"
        result={endingSoon}
        columns={4}
      />
      <FeaturedRow
        title="Featured this week"
        sub="Hand-picked premium parcels with verified covenants."
        sortLink="/browse"
        result={justListed}
        emptyMessage="No new listings yet."
        columns={3}
      />
      <TrustStrip />
      <FeaturedRow
        title="Trending across regions"
        sub="Most-watched parcels right now."
        sortLink="/browse?sort=most_bids"
        result={mostActive}
        emptyMessage="No active bidding to highlight right now."
        columns={4}
      />
    </>
  );
}
