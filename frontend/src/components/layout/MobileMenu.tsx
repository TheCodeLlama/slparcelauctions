"use client";

import { Dialog, DialogPanel } from "@headlessui/react";
import Link from "next/link";
import { X } from "@/components/ui/icons";
import { Button, IconButton, ThemeToggle } from "@/components/ui";
import { useAuth } from "@/lib/auth";
import { useWallet } from "@/lib/wallet/use-wallet";
import { NavLink } from "./NavLink";

type MobileMenuProps = {
  open: boolean;
  onClose: () => void;
};

export function MobileMenu({ open, onClose }: MobileMenuProps) {
  const { status, user } = useAuth();
  const verified = status === "authenticated" && user.verified;
  const { data: wallet } = useWallet(verified);

  return (
    <Dialog open={open} onClose={onClose} className="md:hidden relative z-50">
      <div
        className="fixed inset-0 bg-fg/40 backdrop-blur-sm"
        aria-hidden="true"
      />
      <div className="fixed inset-y-0 right-0 flex w-full max-w-[300px]">
        <DialogPanel className="flex w-full flex-col border-l border-border bg-bg">
          <div className="flex items-center justify-between border-b border-border px-5 py-4">
            {status === "authenticated" ? (
              <div className="min-w-0">
                <div className="truncate text-sm font-semibold text-fg">
                  {user.displayName}
                </div>
                <div className="font-mono text-xs text-fg-subtle">
                  {user.verified ? "Verified" : "Unverified"}
                </div>
              </div>
            ) : (
              <div className="text-sm font-semibold text-fg">Menu</div>
            )}
            <IconButton
              aria-label="Close menu"
              variant="tertiary"
              onClick={onClose}
            >
              <X />
            </IconButton>
          </div>

          <nav className="flex flex-col gap-1 p-3">
            <NavLink variant="mobile" href="/browse" onClick={onClose}>
              Browse
            </NavLink>
            <NavLink variant="mobile" href="/listings/create" onClick={onClose}>
              Sell parcel
            </NavLink>
            <NavLink variant="mobile" href="/dashboard" onClick={onClose}>
              Dashboard
            </NavLink>
            {verified && (
              <NavLink variant="mobile" href="/wallet" onClick={onClose}>
                Wallet{wallet ? ` · L$ ${wallet.available.toLocaleString()}` : ""}
              </NavLink>
            )}
            {status === "authenticated" && user.role === "ADMIN" && (
              <NavLink variant="mobile" href="/admin" onClick={onClose}>
                Admin
              </NavLink>
            )}
          </nav>

          <div className="flex items-center justify-between border-t border-border px-5 py-3">
            <span className="text-xs text-fg-subtle">Theme</span>
            <ThemeToggle />
          </div>

          {status !== "authenticated" && (
            <div className="flex flex-col gap-2 border-t border-border p-3">
              <Link href="/login" onClick={onClose}>
                <Button variant="tertiary" fullWidth>
                  Sign in
                </Button>
              </Link>
              <Link href="/register" onClick={onClose}>
                <Button variant="primary" fullWidth>
                  Register
                </Button>
              </Link>
            </div>
          )}

          <div className="mt-auto flex flex-wrap gap-4 border-t border-border px-5 py-3">
            <FooterLink href="/about" onClose={onClose}>About</FooterLink>
            <FooterLink href="/contact" onClose={onClose}>Contact</FooterLink>
            <FooterLink href="/terms" onClose={onClose}>Terms</FooterLink>
          </div>
        </DialogPanel>
      </div>
    </Dialog>
  );
}

function FooterLink({
  href,
  onClose,
  children,
}: {
  href: string;
  onClose: () => void;
  children: React.ReactNode;
}) {
  return (
    <Link
      href={href}
      onClick={onClose}
      className="text-xs text-fg-muted hover:text-fg"
    >
      {children}
    </Link>
  );
}
