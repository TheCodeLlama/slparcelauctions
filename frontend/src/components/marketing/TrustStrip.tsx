import { ShieldCheck, Lock, Gavel, RefreshCw } from "@/components/ui/icons";
import type { ReactNode } from "react";

const ITEMS: Array<{ icon: ReactNode; title: string; body: string }> = [
  {
    icon: <ShieldCheck className="size-[22px]" aria-hidden />,
    title: "Escrow on every sale",
    body: "L$ is locked until both parties confirm the parcel transfer. Disputes get human review within 24 hours.",
  },
  {
    icon: <Lock className="size-[22px]" aria-hidden />,
    title: "Verified sellers",
    body: "Account age, sales history, and identity check filters keep bots and scammers out of the marketplace.",
  },
  {
    icon: <Gavel className="size-[22px]" aria-hidden />,
    title: "Fair bidding",
    body: "Anti-snipe extensions, proxy bidding, and transparent bid histories prevent last-second rug pulls.",
  },
  {
    icon: <RefreshCw className="size-[22px]" aria-hidden />,
    title: "Money-back guarantee",
    body: "If a parcel does not match its listing, we refund your full bid and any associated fees, no questions.",
  },
];

export function TrustStrip() {
  return (
    <section className="mx-auto w-full max-w-[var(--container-w)] px-6 py-10">
      <div className="rounded-xl bg-bg-subtle p-8">
        <div className="grid grid-cols-1 gap-8 sm:grid-cols-2 md:grid-cols-4">
          {ITEMS.map((item) => (
            <div key={item.title}>
              <div className="mb-3.5 grid size-10 place-items-center rounded-md bg-brand-soft text-brand">
                {item.icon}
              </div>
              <div className="mb-1.5 text-sm font-semibold text-fg">{item.title}</div>
              <div className="text-[13px] leading-[1.5] text-fg-muted">{item.body}</div>
            </div>
          ))}
        </div>
      </div>
    </section>
  );
}
