// export/realty-groups/components/StarPicker.tsx
"use client";

import { useState } from "react";
import { Star } from "lucide-react";
import { cn } from "../lib/cn";

interface StarPickerProps {
  value: number;
  onChange: (value: number) => void;
}

export function StarPicker({ value, onChange }: StarPickerProps) {
  const [hover, setHover] = useState<number | null>(null);
  const display = hover ?? value;

  return (
    <div className="inline-flex gap-0.5">
      {[1, 2, 3, 4, 5].map((i) => {
        const halfVal = i - 0.5;
        const fullVal = i;
        const isHalf = display >= halfVal && display < fullVal;
        const isFull = display >= fullVal;

        return (
          <span
            key={i}
            className="relative inline-block w-5 h-5 leading-none"
          >
            <Star className="absolute inset-0 w-5 h-5 text-fg-subtle fill-current" />
            {isFull && (
              <Star className="absolute inset-0 w-5 h-5 text-brand fill-current" />
            )}
            {isHalf && (
              <span className="absolute left-0 top-0 w-1/2 h-5 overflow-hidden">
                <Star className="block w-5 h-5 text-brand fill-current" />
              </span>
            )}
            <button
              type="button"
              onClick={() => onChange(value === halfVal ? 0 : halfVal)}
              onMouseEnter={() => setHover(halfVal)}
              onMouseLeave={() => setHover(null)}
              className="absolute left-0 top-0 w-1/2 h-full bg-transparent cursor-pointer p-0 appearance-none border-none focus:outline-none"
              aria-label={`${halfVal} stars and up`}
            />
            <button
              type="button"
              onClick={() => onChange(value === fullVal ? 0 : fullVal)}
              onMouseEnter={() => setHover(fullVal)}
              onMouseLeave={() => setHover(null)}
              className="absolute right-0 top-0 w-1/2 h-full bg-transparent cursor-pointer p-0 appearance-none border-none focus:outline-none"
              aria-label={`${fullVal} stars and up`}
            />
          </span>
        );
      })}
    </div>
  );
}
