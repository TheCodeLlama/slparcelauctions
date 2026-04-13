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
          {/*
            Bento order is load-bearing for the 3-column grid with auto-flow:
              Row 1: [Real-Time Bidding lg] [Secure Escrow sm]
              Row 2: [Snipe Protection sm]  [Verified Listings lg]
              Row 3: [Reputation System lg] [Proxy Bidding sm]
            All lg cards auto-apply the dark variant (white in light mode /
            near-black in dark mode + gold radial blur) — the `variant` prop
            is only needed on sm cards that deviate from the surface default.
          */}
          <FeatureCard
            size="lg"
            icon={<Zap className="size-8" />}
            title="Real-Time Bidding"
            body="Our low-latency engine updates bids in milliseconds, ensuring you never miss a critical moment in high-stakes auctions."
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
            icon={<Timer className="size-8" />}
            title="Snipe Protection"
            body="Last-minute bids automatically extend auction windows, preventing unfair last-second bidding tactics."
          />
          <FeatureCard
            size="lg"
            icon={<BadgeCheck className="size-8" />}
            title="Verified Listings"
            body="Every parcel is cross-referenced with region data to confirm tier, covenant, and dimensions before listing."
          />
          <FeatureCard
            size="lg"
            icon={<Star className="size-8" />}
            title="Reputation System"
            body="Trade with confidence using our transparent historical performance metrics for every buyer and seller."
          />
          <FeatureCard
            size="sm"
            icon={<Bot className="size-8" />}
            title="Proxy Bidding"
            body="Set your maximum price and let our system bid incrementally on your behalf to win at the best possible price."
          />
        </div>
      </div>
    </section>
  );
}
