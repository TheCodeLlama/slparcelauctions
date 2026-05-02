"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import type { ReactNode } from "react";
import { cn } from "@/lib/cn";

type NavLinkProps = {
  href: string;
  variant: "header" | "mobile";
  onClick?: () => void;
  children: ReactNode;
};

export function NavLink({ href, variant, onClick, children }: NavLinkProps) {
  const pathname = usePathname();
  const isActive =
    pathname === href || (href !== "/" && pathname.startsWith(href));

  const base =
    variant === "header"
      ? "rounded-sm px-3 py-2 text-sm font-medium transition-colors"
      : "block rounded-sm px-3 py-2 text-sm font-medium transition-colors";

  const state = isActive
    ? "bg-bg-muted text-fg"
    : "text-fg-muted hover:bg-bg-hover hover:text-fg";

  return (
    <Link
      href={href}
      onClick={onClick}
      aria-current={isActive ? "page" : undefined}
      className={cn(base, state)}
    >
      {children}
    </Link>
  );
}
