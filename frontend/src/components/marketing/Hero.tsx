// frontend/src/components/marketing/Hero.tsx
"use client";

import Link from "next/link";
import { Button } from "@/components/ui";
import { useAuth } from "@/lib/auth";
import { LivePill } from "./LivePill";
import { HeroFeaturedParcel } from "./HeroFeaturedParcel";

export function Hero() {
  const { status } = useAuth();
  const isAuthenticated = status === "authenticated";

  const secondaryHref = isAuthenticated ? "/dashboard" : "/register";
  const secondaryLabel = isAuthenticated ? "Go to Dashboard" : "Start Selling";

  return (
    <section className="relative min-h-[560px] overflow-hidden bg-surface md:min-h-[720px]">
      <div className="mx-auto grid w-full max-w-7xl grid-cols-12 gap-8 px-8 py-20 md:py-28">
        <div className="col-span-12 flex flex-col justify-center lg:col-span-7">
          <LivePill className="mb-6">Live Auctions Active</LivePill>

          <h1 className="mb-8 font-display text-5xl font-extrabold leading-[1.05] tracking-tight text-on-surface md:text-7xl">
            Buy &amp; Sell Second Life Land at Auction
          </h1>

          <p className="mb-10 max-w-xl text-xl leading-relaxed text-on-surface-variant">
            The premium digital land curator. Secure your virtual footprint through our
            verified auction house, featuring real-time bidding and exclusive parcel listings.
          </p>

          <div className="flex flex-wrap gap-4">
            <Link href="/browse">
              <Button variant="primary" size="lg">Browse Listings</Button>
            </Link>
            <Link href={secondaryHref}>
              <Button variant="secondary" size="lg">{secondaryLabel}</Button>
            </Link>
          </div>
        </div>

        <div className="hidden lg:col-span-5 lg:flex lg:items-center lg:justify-center">
          <HeroFeaturedParcel />
        </div>
      </div>
    </section>
  );
}
