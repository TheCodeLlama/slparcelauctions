/**
 * Display-only helpers used by the `/groups` directory template.
 * Kept here (not in `@/lib/cn`) so the shared `cn` utility stays narrow.
 */
export function formatFounded(iso: string): string {
  const d = new Date(iso);
  return d.toLocaleDateString("en-US", { month: "short", year: "numeric" });
}

export function initialsOf(name: string): string {
  return name
    .split(/\s+/)
    .slice(0, 2)
    .map((w) => w[0] ?? "")
    .join("")
    .toUpperCase();
}
