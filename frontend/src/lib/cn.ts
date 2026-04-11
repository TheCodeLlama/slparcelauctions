import { type ClassValue, clsx } from "clsx";
import { extendTailwindMerge } from "tailwind-merge";

/**
 * The project uses a custom Material Design 3 type scale (text-display-*,
 * text-headline-*, text-title-*, text-body-*, text-label-*). tailwind-merge
 * doesn't know about these by default and treats them as the same conflict
 * group as text-color utilities (text-on-primary, etc.), so the last one
 * written wins and the other is silently dropped.
 *
 * extendTailwindMerge registers them in the `font-size` group so they are
 * never confused with text-color utilities.
 */
const twMerge = extendTailwindMerge({
  extend: {
    classGroups: {
      "font-size": [
        "text-display-lg",
        "text-display-md",
        "text-display-sm",
        "text-headline-lg",
        "text-headline-md",
        "text-headline-sm",
        "text-title-lg",
        "text-title-md",
        "text-title-sm",
        "text-body-lg",
        "text-body-md",
        "text-body-sm",
        "text-label-lg",
        "text-label-md",
        "text-label-sm",
      ],
    },
  },
});

/**
 * Compose Tailwind class strings. Filters falsy values via clsx, then
 * dedupes conflicting Tailwind utilities via tailwind-merge so that
 * consumer-passed `className` always wins over component base classes.
 *
 * The custom merger is configured with the project's M3 type scale tokens
 * so they coexist with text-color utilities without conflict.
 */
export function cn(...inputs: ClassValue[]): string {
  return twMerge(clsx(inputs));
}
