"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { cn } from "@/lib/cn";

const SECTIONS = [
  { href: "/settings/profile", label: "Profile" },
  { href: "/settings/notifications", label: "Notifications" },
];

export function SettingsNav() {
  const pathname = usePathname();
  return (
    <nav className="mb-6 flex gap-4 border-b border-border-subtle">
      {SECTIONS.map((section) => {
        const active = pathname === section.href;
        return (
          <Link
            key={section.href}
            href={section.href}
            className={cn(
              "px-1 pb-2 text-sm transition-colors",
              active
                ? "border-b-2 border-brand text-fg font-medium"
                : "text-fg-muted hover:text-fg",
            )}
            aria-current={active ? "page" : undefined}
          >
            {section.label}
          </Link>
        );
      })}
    </nav>
  );
}
