// frontend/src/components/marketing/FeatureCard.tsx
"use client";

import Image from "next/image";
import { useTheme } from "next-themes";
import { useEffect, useState, type ReactNode } from "react";
import { cn } from "@/lib/cn";

type FeatureCardSize = "sm" | "lg";
type FeatureCardVariant = "surface" | "primary" | "dark";

type FeatureCardProps = {
  icon: ReactNode;
  title: string;
  body: string;
  size?: FeatureCardSize;
  variant?: FeatureCardVariant;
  backgroundImage?: {
    light: string;
    dark: string;
  };
};

const sizeClasses: Record<FeatureCardSize, string> = {
  sm: "",
  lg: "md:col-span-2",
};

const variantClasses: Record<FeatureCardVariant, string> = {
  surface: "bg-surface-container text-on-surface",
  primary: "bg-primary-container text-on-primary-container",
  // `dark` is the visually-distinct bento treatment used by every `size="lg"`
  // card in the bento. Uses surface-container-lowest so it flips OPPOSITE to
  // the other cards:
  //   - light mode: pure white (#ffffff)  — pops against the section's ivory bg
  //   - dark mode:  near-black (#0c0e10)  — visibly darker than the neighboring
  //                                          surface-container cards (#1e2022)
  //
  // The name "dark" is a bit of a misnomer post-swap; it describes the dark-mode
  // appearance (the mode where the visual signature — dark bg + gold radiant —
  // lands). Kept as-is to avoid a downstream variant rename.
  //
  // NOTE: `size="lg"` cards always get this treatment regardless of what the
  // caller passes as `variant` — see the `effectiveVariant` computation below.
  dark: "bg-surface-container-lowest text-on-surface",
};

export function FeatureCard({
  icon,
  title,
  body,
  size = "sm",
  variant = "surface",
  backgroundImage,
}: FeatureCardProps) {
  const { resolvedTheme } = useTheme();
  const [mounted, setMounted] = useState(false);

  // See FOOTGUNS §F.21 for the hydration guard pattern.
  useEffect(() => {
    setMounted(true); // eslint-disable-line react-hooks/set-state-in-effect
  }, []);

  const bgSrc = backgroundImage
    ? mounted && resolvedTheme === "dark"
      ? backgroundImage.dark
      : backgroundImage.light
    : null;

  // Every `size="lg"` card in the bento wears the dark treatment (white in
  // light mode / near-black in dark mode + gold radial blur). The caller's
  // `variant` prop only applies when `size="sm"`. This keeps the bento's
  // large-card rhythm consistent without making every call site repeat
  // `variant="dark"`.
  const effectiveVariant: FeatureCardVariant = size === "lg" ? "dark" : variant;

  const iconColorClass =
    effectiveVariant === "dark"
      ? "text-primary-fixed-dim"
      : effectiveVariant === "primary"
        ? "text-on-primary-container"
        : "text-primary";

  const bodyColorClass =
    effectiveVariant === "primary"
      ? "text-sm opacity-80"
      : "text-sm text-on-surface-variant";

  return (
    <div
      className={cn(
        "group relative flex flex-col justify-between gap-8 overflow-hidden rounded-xl p-10",
        sizeClasses[size],
        variantClasses[effectiveVariant]
      )}
    >
      {bgSrc ? (
        <div className="pointer-events-none absolute right-0 bottom-0 h-full w-1/2 opacity-10 transition-transform duration-500 group-hover:scale-110">
          <Image
            src={bgSrc}
            alt=""
            fill
            sizes="(min-width: 768px) 33vw, 0px"
            className="object-cover"
            aria-hidden
          />
        </div>
      ) : null}

      {effectiveVariant === "dark" ? (
        <div
          className="pointer-events-none absolute -right-20 -bottom-20 h-64 w-64 rounded-full bg-primary/20 blur-3xl"
          aria-hidden
        />
      ) : null}

      <div className={cn("relative z-10", iconColorClass)}>{icon}</div>

      <div className="relative z-10">
        <h3
          className={cn(
            "mb-3 font-display font-bold",
            size === "lg" ? "text-3xl" : "text-2xl"
          )}
        >
          {title}
        </h3>
        <p className={cn(size === "lg" ? "max-w-md" : "", bodyColorClass)}>
          {body}
        </p>
      </div>
    </div>
  );
}
