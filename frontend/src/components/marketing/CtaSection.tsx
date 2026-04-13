// frontend/src/components/marketing/CtaSection.tsx
"use client";

import Link from "next/link";
import { Button } from "@/components/ui";
import { useAuth } from "@/lib/auth";

export function CtaSection() {
  const { status } = useAuth();

  // Authenticated users don't need a sign-up prompt. Return null so the section
  // disappears entirely — no empty container, no leftover padding.
  if (status === "authenticated") return null;

  return (
    <section className="px-8 py-32">
      <div className="relative mx-auto max-w-7xl overflow-hidden rounded-[2rem] bg-gradient-to-br from-primary to-primary-container p-12 text-center md:p-24">
        {/*
          Inline style: Tailwind has no radial-gradient utility. This decorative
          dot pattern is the one inline-style exception in the codebase,
          allowlisted in frontend/scripts/verify-no-inline-styles.sh. See spec
          §6.8 and FOOTGUNS for the policy.
        */}
        <div
          className="absolute inset-0 opacity-20"
          style={{
            backgroundImage: "radial-gradient(var(--color-on-primary) 1px, transparent 1px)",
            backgroundSize: "40px 40px",
          }}
          aria-hidden
        />
        <div className="relative z-10">
          <h2 className="mb-8 font-display text-4xl font-extrabold tracking-tight text-on-primary md:text-6xl">
            Ready to acquire your next parcel?
          </h2>
          <p className="mx-auto mb-12 max-w-2xl text-xl text-on-primary/80">
            Join thousands of curators building their digital footprint on SLPA.
          </p>
          <div className="flex flex-wrap justify-center gap-6">
            <Link href="/register">
              <Button variant="primary" size="lg">Create Free Account</Button>
            </Link>
            <Link href="/browse">
              <Button variant="secondary" size="lg">View Active Auctions</Button>
            </Link>
          </div>
        </div>
      </div>
    </section>
  );
}
