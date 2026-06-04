"use client";

import { useEffect, useState } from "react";
import { FeaturedBoardView } from "./FeaturedBoardView";
import type { FeaturedBoardListing } from "@/types/promotion";

interface Props {
  listings: FeaturedBoardListing[];
  cycleSeconds: number;
}

function indexAt(epochSeconds: number, cycleSeconds: number, length: number) {
  return Math.floor(epochSeconds / cycleSeconds) % length;
}

export function FeaturedBoardCycler({ listings, cycleSeconds }: Props) {
  const [now, setNow] = useState(() => Math.floor(Date.now() / 1000));

  useEffect(() => {
    if (listings.length < 2) return;
    const id = setInterval(
      () => setNow(Math.floor(Date.now() / 1000)),
      cycleSeconds * 1000,
    );
    return () => clearInterval(id);
  }, [listings.length, cycleSeconds]);

  if (listings.length === 0) return null;
  const idx = listings.length === 1 ? 0 : indexAt(now, cycleSeconds, listings.length);
  return <FeaturedBoardView listing={listings[idx]} />;
}
