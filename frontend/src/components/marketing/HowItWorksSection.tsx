// frontend/src/components/marketing/HowItWorksSection.tsx
import {
  CreditCard,
  Gavel,
  ListChecks,
  ShieldCheck,
} from "@/components/ui/icons";
import { HowItWorksStep } from "./HowItWorksStep";

const STEPS = [
  {
    icon: <ShieldCheck className="size-6" />,
    title: "Verify",
    body: "Identity and land ownership verification to ensure a safe environment for all participants.",
  },
  {
    icon: <ListChecks className="size-6" />,
    title: "List",
    body: "List your parcel with detailed dimensions, location metrics, and professional photography.",
  },
  {
    icon: <Gavel className="size-6" />,
    title: "Auction",
    body: "Engage in high-velocity real-time bidding with automated proxy options and sniping protection.",
  },
  {
    icon: <CreditCard className="size-6" />,
    title: "Settle",
    body: "Secure escrow services ensure funds and land ownership transfer smoothly and instantly.",
  },
] as const;

export function HowItWorksSection() {
  return (
    <section className="bg-surface-container-low px-8 py-32">
      <div className="mx-auto max-w-7xl">
        <div className="mb-20 flex flex-col justify-between gap-8 lg:flex-row lg:items-end">
          <div className="max-w-2xl">
            <h2 className="mb-6 font-display text-4xl font-bold text-on-surface md:text-5xl">
              Simple, Secure, Curated.
            </h2>
            <p className="text-lg text-on-surface-variant">
              We&apos;ve refined the process of digital land acquisition into four seamless
              steps designed for professional curators.
            </p>
          </div>
        </div>
        <div className="grid grid-cols-1 gap-8 md:grid-cols-2 lg:grid-cols-4">
          {STEPS.map((step) => (
            <HowItWorksStep
              key={step.title}
              icon={step.icon}
              title={step.title}
              body={step.body}
            />
          ))}
        </div>
      </div>
    </section>
  );
}
