// frontend/src/components/marketing/FeaturesSection.tsx
import {
  BadgeCheck,
  Bot,
  Shield,
  Star,
  Timer,
  Zap,
} from "@/components/ui/icons";
import { FeatureCard } from "./FeatureCard";

export function FeaturesSection() {
  return (
    <section className="bg-surface px-8 py-32">
      <div className="mx-auto max-w-7xl">
        <div className="mb-20 text-center">
          <h2 className="mb-6 font-display text-4xl font-bold text-on-surface md:text-5xl">
            Designed for Performance
          </h2>
          <p className="mx-auto max-w-2xl text-lg text-on-surface-variant">
            Engineered to provide the most reliable land trading platform in the virtual ecosystem.
          </p>
        </div>

        <div className="grid grid-cols-1 gap-6 md:grid-cols-3">
          <FeatureCard
            size="lg"
            variant="surface"
            icon={<Zap className="size-8" />}
            title="Real-Time Bidding"
            body="Our low-latency engine updates bids in milliseconds, ensuring you never miss a critical moment in high-stakes auctions."
            backgroundImage={{
              light: "/landing/bidding-bg.png",
              dark: "/landing/bidding-bg.png",
            }}
          />
          <FeatureCard
            size="sm"
            variant="primary"
            icon={<Shield className="size-8" />}
            title="Secure Escrow"
            body="Automated multi-sig escrow for every transaction, protecting both the buyer's capital and the seller's asset."
          />
          <FeatureCard
            size="sm"
            variant="surface"
            icon={<Timer className="size-8" />}
            title="Snipe Protection"
            body="Last-minute bids automatically extend auction windows, preventing unfair last-second bidding tactics."
          />
          <FeatureCard
            size="lg"
            variant="dark"
            icon={<BadgeCheck className="size-8" />}
            title="Verified Listings"
            body="Every parcel is cross-referenced with region data to confirm tier, covenant, and dimensions before listing."
          />
          <FeatureCard
            size="sm"
            variant="surface"
            icon={<Bot className="size-8" />}
            title="Proxy Bidding"
            body="Set your maximum price and let our system bid incrementally on your behalf to win at the best possible price."
          />
          <FeatureCard
            size="lg"
            variant="surface"
            icon={<Star className="size-8" />}
            title="Reputation System"
            body="Trade with confidence using our transparent historical performance metrics for every buyer and seller."
          />
        </div>
      </div>
    </section>
  );
}
