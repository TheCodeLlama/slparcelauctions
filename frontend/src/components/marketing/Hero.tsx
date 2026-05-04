"use client";

import Link from "next/link";
import type { AuctionSearchResultDto } from "@/types/search";
import { Button, Eyebrow } from "@/components/ui";
import { ArrowRight } from "@/components/ui/icons";
import { useAuth } from "@/lib/auth";
import { HeroFeaturedStack } from "./HeroFeaturedStack";

export interface HeroProps {
  /** Up to 3 featured auctions to render in the right-side card stack. May be
   *  empty during SSR fallback — the stack handles its own empty state. */
  featured: AuctionSearchResultDto[];
}

export function Hero({ featured }: HeroProps) {
  const { status } = useAuth();
  const isAuthenticated = status === "authenticated";
  const sellHref = isAuthenticated ? "/listings/create" : "/register";
  const sellLabel = isAuthenticated ? "List your parcel" : "Start selling";

  return (
    <section className="border-b border-border bg-bg-subtle">
      <div className="mx-auto grid w-full max-w-[var(--container-w)] grid-cols-1 items-center gap-12 px-6 py-14 lg:grid-cols-[1.1fr_1fr] lg:gap-14 lg:py-16">
        <div>
          <Eyebrow className="mb-4">The marketplace for virtual land</Eyebrow>
          <h1 className="m-0 text-4xl font-extrabold leading-[1.05] tracking-tight text-fg md:text-5xl lg:text-[48px]">
            Auction parcels with real escrow protection.
          </h1>
          <p className="mt-4 max-w-[540px] text-base leading-[1.5] text-fg-muted md:text-lg">
            Find premium Second Life parcels at fair prices. Every transaction
            is protected by SLPA Escrow, so you don&apos;t lose L$ to bad-faith sellers.
          </p>
          <div className="mt-7 flex flex-wrap gap-2.5">
            <Link href="/browse">
              <Button variant="primary" size="lg" rightIcon={<ArrowRight className="size-4" aria-hidden />}>
                Browse auctions
              </Button>
            </Link>
            <Link href={sellHref}>
              <Button variant="secondary" size="lg">{sellLabel}</Button>
            </Link>
          </div>
        </div>
        <HeroFeaturedStack featured={featured} />
      </div>
    </section>
  );
}
