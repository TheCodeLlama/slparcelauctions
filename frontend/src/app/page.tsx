// frontend/src/app/page.tsx
import { Hero } from "@/components/marketing/Hero";
import { HowItWorksSection } from "@/components/marketing/HowItWorksSection";
import { FeaturesSection } from "@/components/marketing/FeaturesSection";
import { CtaSection } from "@/components/marketing/CtaSection";

export default function HomePage() {
  return (
    <>
      <Hero />
      <HowItWorksSection />
      <FeaturesSection />
      <CtaSection />
    </>
  );
}
