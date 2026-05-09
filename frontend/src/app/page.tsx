import { Hero } from "@/components/marketing/Hero";
import { FeaturedRow } from "@/components/marketing/FeaturedRow";
import { TrustStrip } from "@/components/marketing/TrustStrip";
import { fetchFeatured } from "@/lib/api/auctions-search";

/**
 * Render at request time, never at build time. Static prerendering would
 * couple the Amplify build to whatever the backend happens to return at
 * build time — a single bad-shape field on a featured auction (e.g. an
 * accidentally-leaked entity row inside a list) crashes the build and
 * blocks every other page from deploying. The home page's data
 * (countdowns, current bids) changes on every visit anyway, so a static
 * snapshot is never the right cache.
 */
export const dynamic = "force-dynamic";

/**
 * Homepage server component. Fans out the three rail fetches under a
 * single Promise.allSettled so one endpoint's 5xx doesn't cascade into a
 * full-page 500 — failing rails render a neutral placeholder instead.
 *
 * Section order: Hero -> Featured -> TrustStrip -> Ending Soon -> Trending.
 * The Hero stack pulls from the new Featured rail (paid-promotion-aware),
 * not from Ending Soon. Ending Soon is hidden when its content array is
 * fulfilled-empty (per issue #155); a rejected fetch still renders the
 * "unavailable" placeholder so admins can spot rail outages.
 *
 * Promise.allSettled (not Promise.all) is load-bearing: Promise.all
 * short-circuits on the first rejection, which would couple the three
 * independent rails.
 */
export default async function HomePage() {
  const [featured, endingSoon, trending] = await Promise.allSettled([
    fetchFeatured("featured"),
    fetchFeatured("ending-soon"),
    fetchFeatured("trending"),
  ]);

  const heroFeatured =
    featured.status === "fulfilled" ? featured.value.content.slice(0, 3) : [];

  const hideEndingSoon =
    endingSoon.status === "fulfilled" && endingSoon.value.content.length === 0;

  return (
    <>
      <Hero featured={heroFeatured} />
      <FeaturedRow
        title="Featured"
        sub="Hand-picked premium parcels."
        sortLink="/browse"
        result={featured}
        emptyMessage="No featured listings right now."
        columns={4}
      />
      <TrustStrip />
      {hideEndingSoon ? null : (
        <FeaturedRow
          title="Ending soon"
          sub="Auctions closing in the next few hours."
          sortLink="/browse?sort=ending_soonest"
          result={endingSoon}
          columns={4}
        />
      )}
      <FeaturedRow
        title="Trending"
        sub="Most-watched parcels right now."
        sortLink="/browse?sort=most_bids"
        result={trending}
        emptyMessage="No active bidding to highlight right now."
        columns={4}
      />
    </>
  );
}
