// frontend/src/components/marketing/HowItWorksStep.tsx
import type { ReactNode } from "react";

type HowItWorksStepProps = {
  icon: ReactNode;
  title: string;
  body: string;
};

export function HowItWorksStep({ icon, title, body }: HowItWorksStepProps) {
  return (
    <div className="rounded-xl bg-surface-container-lowest p-8 transition-transform duration-300 hover:-translate-y-2">
      <div className="mb-6 flex size-12 items-center justify-center rounded-lg bg-primary/10 text-primary">
        {icon}
      </div>
      <h3 className="mb-3 font-display text-xl font-bold text-on-surface">{title}</h3>
      <p className="text-sm leading-relaxed text-on-surface-variant">{body}</p>
    </div>
  );
}
