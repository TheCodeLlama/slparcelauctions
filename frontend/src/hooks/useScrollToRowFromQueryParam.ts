"use client";
import { useEffect } from "react";
import { useSearchParams } from "next/navigation";

/**
 * On mount and when the matching `dataKey` changes in the dependency array,
 * reads `?<queryParam>=<value>` from the URL, looks up
 * `[data-testid="<testidPrefix>-<value>"]` in the DOM, scrolls it into view,
 * and applies a temporary highlight class for 1.5s.
 *
 * Used by drill-down landing pages (the per-user wallet tab, the
 * infrastructure page tabs) so a click on the global ledger view's `→` link
 * lands directly on the relevant row.
 *
 * The {@code deps} array should include any state that controls when the
 * target row first appears in the DOM (e.g. the page query's data, the
 * active tab) — without it, the hook may run before the row exists.
 */
export function useScrollToRowFromQueryParam(
  queryParam: string,
  testidPrefix: string,
  deps: React.DependencyList,
) {
  const searchParams = useSearchParams();
  const value = searchParams?.get(queryParam) ?? null;

  useEffect(() => {
    if (!value) return;
    const el = document.querySelector(`[data-testid="${testidPrefix}-${value}"]`);
    if (!el) return;
    el.scrollIntoView({ block: "center", behavior: "smooth" });
    el.classList.add("ring-2", "ring-brand", "transition-shadow");
    const timer = setTimeout(() => {
      el.classList.remove("ring-2", "ring-brand", "transition-shadow");
    }, 1500);
    return () => clearTimeout(timer);
    // eslint-disable-next-line react-hooks/exhaustive-deps -- intentional: caller supplies deps that gate when the row exists.
  }, [value, ...deps]);
}
