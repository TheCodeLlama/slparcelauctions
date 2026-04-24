// frontend/src/app/page.tsx
import { Hero } from "@/components/marketing/Hero";
import { HowItWorksSection } from "@/components/marketing/HowItWorksSection";
import { FeaturesSection } from "@/components/marketing/FeaturesSection";
import { CtaSection } from "@/components/marketing/CtaSection";
import { FeaturedRow } from "@/components/marketing/FeaturedRow";
import { fetchFeatured } from "@/lib/api/auctions-search";

/**
 * Homepage server component. Fans out the three featured-rail fetches under
 * a single {@link Promise.allSettled} so one endpoint's 5xx doesn't cascade
 * into a full-page 500 — failing rails render a neutral placeholder instead.
 *
 * NB: {@code Promise.allSettled} (not {@code Promise.all}) is load-bearing
 * here. {@code Promise.all} short-circuits on the first rejection, which
 * would couple the three independent rails; {@code allSettled} never
 * rejects, so every rail lands in the render regardless of the others'
 * outcomes.
 *
 * StatsBar is intentionally omitted. Per product decision, launching with
 * low activity numbers reads as a liability rather than social proof — the
 * backend {@code /stats/public} endpoint is live but the component is
 * deferred until activity threshold is met. See
 * {@code docs/implementation/DEFERRED_WORK.md}.
 */
export default async function HomePage() {
  const [endingSoon, justListed, mostActive] = await Promise.allSettled([
    fetchFeatured("ending-soon"),
    fetchFeatured("just-listed"),
    fetchFeatured("most-active"),
  ]);

  return (
    <>
      <Hero />
      <FeaturedRow
        title="Ending Soon"
        sortLink="/browse?sort=ending_soonest"
        result={endingSoon}
      />
      <FeaturedRow
        title="Just Listed"
        sortLink="/browse"
        result={justListed}
        emptyMessage="No new listings yet."
      />
      <FeaturedRow
        title="Most Active"
        sortLink="/browse?sort=most_bids"
        result={mostActive}
        emptyMessage="No active bidding to highlight right now."
      />
      <HowItWorksSection />
      <FeaturesSection />
      <CtaSection />
    </>
  );
}
