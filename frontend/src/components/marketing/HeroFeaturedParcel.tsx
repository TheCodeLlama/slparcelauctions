// frontend/src/components/marketing/HeroFeaturedParcel.tsx
"use client";

import Image from "next/image";
import { useTheme } from "next-themes";
import { useEffect, useState } from "react";

const LIGHT_SRC = "/landing/hero-parcel-light.png";
const DARK_SRC = "/landing/hero-parcel-dark.png";

export function HeroFeaturedParcel() {
  const { resolvedTheme } = useTheme();
  const [mounted, setMounted] = useState(false);

  // Prevent SSR/CSR mismatch: next-themes only knows the real theme after
  // mount. Before mount, render the light variant. After mount, swap to the
  // correct variant based on resolvedTheme.
  //
  // See FOOTGUNS §F.21.
  useEffect(() => {
    setMounted(true); // eslint-disable-line react-hooks/set-state-in-effect
  }, []);

  const src = mounted && resolvedTheme === "dark" ? DARK_SRC : LIGHT_SRC;

  return (
    <div className="group relative aspect-[4/5] w-full overflow-hidden rounded-xl shadow-elevated">
      <Image
        src={src}
        alt="Featured Parcel Preview"
        fill
        sizes="(min-width: 1024px) 41vw, 0px"
        priority
        className="object-cover transition-transform duration-700 group-hover:scale-105"
      />
      <div className="absolute inset-0 bg-gradient-to-t from-black/60 to-transparent" />
      <div className="absolute bottom-8 left-8 right-8 text-white">
        <p className="font-display text-2xl font-bold">Featured Parcel Preview</p>
        <p className="text-sm text-white/80">Live auctions coming soon</p>
      </div>
    </div>
  );
}
