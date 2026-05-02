"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { cn } from "@/lib/cn";

export type TabItem = { id: string; label: string; href: string };

type TabsProps = { tabs: readonly TabItem[]; className?: string };

function findActiveHref(
  pathname: string,
  tabs: readonly TabItem[],
): string | null {
  let best: TabItem | null = null;
  for (const tab of tabs) {
    const matches =
      pathname === tab.href || pathname.startsWith(`${tab.href}/`);
    if (matches && (!best || tab.href.length > best.href.length)) {
      best = tab;
    }
  }
  return best?.href ?? null;
}

export function Tabs({ tabs, className }: TabsProps) {
  const pathname = usePathname();
  const activeHref = findActiveHref(pathname, tabs);

  return (
    <nav
      role="tablist"
      aria-label="Dashboard sections"
      className={cn("flex gap-1 border-b border-border", className)}
    >
      {tabs.map((tab) => {
        const isActive = tab.href === activeHref;
        return (
          <Link
            key={tab.id}
            href={tab.href}
            role="tab"
            aria-selected={isActive}
            aria-controls={`${tab.id}-panel`}
            className={cn(
              "px-4 py-2 text-sm font-medium transition-colors",
              isActive
                ? "text-brand border-b-2 border-brand"
                : "text-fg-muted hover:text-fg",
            )}
          >
            {tab.label}
          </Link>
        );
      })}
    </nav>
  );
}
