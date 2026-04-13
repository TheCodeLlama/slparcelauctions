// frontend/src/components/marketing/LivePill.tsx
//
// Animated "live status" pill with a pulsing ping dot. Used in Hero to draw
// attention to active auctions; designed to be reusable on /browse, /auction/[id],
// or any page that needs a "something is happening right now" badge.
//
// DO NOT ADD HOOKS. This component has no "use client" directive so it can be
// composed by BOTH server-component parents (HowItWorksSection, FeaturesSection)
// AND client-component parents (Hero, CtaSection). Adding useEffect/useState
// without a "use client" directive would break server-side rendering for any
// server-parent consumer. Adding "use client" unnecessarily would ship the pulse
// animation's JS to every page that uses the pill.
//
// The ping animation is pure Tailwind `animate-ping` — no JS needed.
//
// See FOOTGUNS §F.20.

import type { ReactNode } from "react";
import { cn } from "@/lib/cn";

type LivePillProps = {
  children: ReactNode;
  className?: string;
};

export function LivePill({ children, className }: LivePillProps) {
  return (
    <div
      className={cn(
        "inline-flex w-fit items-center gap-2 rounded-full bg-surface-container-highest px-3 py-1",
        className
      )}
    >
      <span className="relative flex h-2 w-2">
        <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-primary opacity-75" />
        <span className="relative inline-flex h-2 w-2 rounded-full bg-primary" />
      </span>
      <span className="text-[10px] font-bold uppercase tracking-widest text-primary">
        {children}
      </span>
    </div>
  );
}
