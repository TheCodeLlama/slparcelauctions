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
      ? "text-body-md transition-colors"
      : "text-headline-sm transition-colors";

  const state = isActive
    ? "text-primary"
    : "text-on-surface-variant hover:text-on-surface";

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
