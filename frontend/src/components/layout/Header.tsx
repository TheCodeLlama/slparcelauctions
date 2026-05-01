"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { MenuIcon } from "@/components/ui/icons";
import {
  Button,
  IconButton,
  ThemeToggle,
} from "@/components/ui";
import { useAuth } from "@/lib/auth";
import { cn } from "@/lib/cn";
import { MobileMenu } from "./MobileMenu";
import { NavLink } from "./NavLink";
import { UserMenuDropdown } from "@/components/auth/UserMenuDropdown";
import { NotificationBell } from "@/components/notifications/NotificationBell";
import { HeaderWalletIndicator } from "@/components/wallet/HeaderWalletIndicator";

export function Header() {
  const [mobileMenuOpen, setMobileMenuOpen] = useState(false);
  const [scrolled, setScrolled] = useState(false);
  const { status, user } = useAuth();

  useEffect(() => {
    const onScroll = () => setScrolled(window.scrollY > 8);
    onScroll();
    window.addEventListener("scroll", onScroll, { passive: true });
    return () => window.removeEventListener("scroll", onScroll);
  }, []);

  return (
    <>
      <header
        className={cn(
          "sticky top-0 z-50 bg-surface/80 backdrop-blur-md transition-shadow",
          scrolled && "shadow-soft"
        )}
      >
        <div className="mx-auto flex h-16 max-w-7xl items-center justify-between px-6">
          <Link
            href="/"
            className="font-display text-xl font-black uppercase tracking-wider text-primary"
          >
            SLPA
          </Link>

          <nav className="hidden md:flex items-center gap-8">
            <NavLink variant="header" href="/browse">Browse</NavLink>
            <NavLink variant="header" href="/dashboard">Dashboard</NavLink>
            {status === "authenticated" && user.verified && (
              <NavLink variant="header" href="/wallet">Wallet</NavLink>
            )}
            <NavLink variant="header" href="/auction/new">Create Listing</NavLink>
            {status === "authenticated" && user.role === "ADMIN" && (
              <NavLink variant="header" href="/admin">Admin</NavLink>
            )}
          </nav>

          <div className="flex items-center gap-2">
            <ThemeToggle />
            <div id="curator-tray-slot" />
            <HeaderWalletIndicator />
            <NotificationBell />

            {status === "loading" ? null : status === "authenticated" ? (
              <UserMenuDropdown user={user} />
            ) : (
              <div className="hidden md:flex items-center gap-2">
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
