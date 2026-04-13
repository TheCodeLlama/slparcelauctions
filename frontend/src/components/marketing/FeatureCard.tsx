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
  dark: "bg-inverse-surface text-inverse-on-surface",
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

  const iconColorClass =
    variant === "dark"
      ? "text-primary-fixed-dim"
      : variant === "primary"
        ? "text-on-primary-container"
        : "text-primary";

  const bodyColorClass =
    variant === "primary"
      ? "text-sm opacity-80"
      : variant === "dark"
        ? "text-sm text-white/60"
        : "text-sm text-on-surface-variant";

  return (
    <div
      className={cn(
        "group relative flex flex-col justify-between gap-8 overflow-hidden rounded-xl p-10",
        sizeClasses[size],
        variantClasses[variant]
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
