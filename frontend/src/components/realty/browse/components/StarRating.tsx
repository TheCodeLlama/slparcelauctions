"use client";

import { Star } from "lucide-react";
import { cn } from "@/lib/cn";

interface StarRatingProps {
  value: number;
  size?: number;
  showNumber?: boolean;
}

export function StarRating({ value, size = 12, showNumber = true }: StarRatingProps) {
  const rounded = Math.round(value);
  return (
    <span className="inline-flex items-center gap-1">
      <span className="inline-flex gap-px">
        {[1, 2, 3, 4, 5].map((i) => (
          <Star
            key={i}
            style={{ width: size, height: size }}
            className={cn(
              i <= rounded ? "text-brand fill-current" : "text-fg-subtle",
            )}
          />
        ))}
      </span>
      {showNumber && (
        <span className="text-xs text-fg-muted font-medium">{value.toFixed(1)}</span>
      )}
    </span>
  );
}
