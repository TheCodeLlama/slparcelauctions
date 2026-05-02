"use client";

import Link from "next/link";
import { useState } from "react";
import { MenuIcon, Search } from "@/components/ui/icons";
import { Button, IconButton, ThemeToggle, WalletPill } from "@/components/ui";
import { useAuth } from "@/lib/auth";
import { cn } from "@/lib/cn";
import { MobileMenu } from "./MobileMenu";
import { NavLink } from "./NavLink";
import { UserMenuDropdown } from "@/components/auth/UserMenuDropdown";
import { NotificationBell } from "@/components/notifications/NotificationBell";

export function Header() {
  const [mobileMenuOpen, setMobileMenuOpen] = useState(false);
  const { status, user } = useAuth();

  return (
    <>
      <header
        className={cn(
          "sticky top-0 z-50 h-[var(--header-h)]",
          "border-b border-border bg-bg/85 backdrop-blur"
        )}
      >
        <div className="mx-auto flex h-full w-full max-w-[var(--container-w)] items-center gap-6 px-6">
          <Link href="/" className="flex shrink-0 items-center gap-2.5">
            <span
              aria-hidden
              className={cn(
                "grid h-7 w-7 place-items-center rounded-[7px]",
                "bg-brand text-white text-[13px] font-extrabold leading-none"
              )}
            >
              SL
            </span>
            <span className="text-base font-bold tracking-tight text-fg">
              Parcels
            </span>
          </Link>

          <nav className="hidden flex-1 items-center gap-1 md:flex">
            <NavLink variant="header" href="/browse">Browse</NavLink>
            <NavLink variant="header" href="/listings/new">Sell parcel</NavLink>
            <NavLink variant="header" href="/dashboard">Dashboard</NavLink>
            {status === "authenticated" && user.role === "ADMIN" && (
              <NavLink variant="header" href="/admin">Admin</NavLink>
            )}
          </nav>

          <div className="flex shrink-0 items-center gap-1.5">
            <IconButton
              aria-label="Search"
              variant="tertiary"
              className="hidden md:inline-flex"
            >
              <Search className="h-[18px] w-[18px]" />
            </IconButton>
            <ThemeToggle />
            <div id="curator-tray-slot" />
            <NotificationBell />
            <WalletPill />

            {status === "loading" ? null : status === "authenticated" ? (
              <UserMenuDropdown user={user} />
            ) : (
              <div className="hidden items-center gap-2 md:flex">
                <Link href="/login">
                  <Button variant="tertiary" size="sm">Sign in</Button>
                </Link>
                <Link href="/register">
                  <Button variant="primary" size="sm">Register</Button>
                </Link>
              </div>
            )}

            <IconButton
              aria-label="Open menu"
              variant="tertiary"
              className="md:hidden"
              onClick={() => setMobileMenuOpen(true)}
            >
              <MenuIcon />
            </IconButton>
          </div>
        </div>
      </header>

      <MobileMenu open={mobileMenuOpen} onClose={() => setMobileMenuOpen(false)} />
    </>
  );
}
