import { type ClassValue, clsx } from "clsx";
import { twMerge } from "tailwind-merge";

/**
 * Compose Tailwind class strings. Filters falsy values via clsx, then
 * dedupes conflicting Tailwind utilities via tailwind-merge so that
 * consumer-passed `className` always wins over component base classes.
 */
export function cn(...inputs: ClassValue[]): string {
  return twMerge(clsx(inputs));
}
