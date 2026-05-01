"use client";

import { Dialog, DialogPanel } from "@headlessui/react";
import Link from "next/link";
import { X } from "@/components/ui/icons";
import { Button, IconButton } from "@/components/ui";
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
        className="fixed inset-0 bg-inverse-surface/40 backdrop-blur-sm"
        aria-hidden="true"
      />
      <div className="fixed inset-y-0 right-0 flex w-full max-w-sm">
        <DialogPanel className="flex w-full flex-col bg-surface-container-low p-6">
          <div className="flex justify-end">
            <IconButton
              aria-label="Close menu"
              variant="tertiary"
              onClick={onClose}
            >
              <X />
            </IconButton>
          </div>

          <nav className="mt-8 flex flex-col gap-6">
            <NavLink variant="mobile" href="/browse" onClick={onClose}>
              Browse
            </NavLink>
            <NavLink variant="mobile" href="/dashboard" onClick={onClose}>
              Dashboard
            </NavLink>
            {verified && (
              <NavLink variant="mobile" href="/wallet" onClick={onClose}>
                Wallet{wallet ? ` · L$ ${wallet.available.toLocaleString()}` : ""}
              </NavLink>
            )}
            <NavLink variant="mobile" href="/auction/new" onClick={onClose}>
              Create Listing
            </NavLink>
            {status === "authenticated" && user.role === "ADMIN" && (
              <NavLink variant="mobile" href="/admin" onClick={onClose}>
                Admin
              </NavLink>
            )}
          </nav>

          <div className="mt-auto flex flex-col gap-3">
            <Link href="/login">
              <Button variant="tertiary" fullWidth>
                Sign in
              </Button>
            </Link>
            <Link href="/register">
              <Button variant="primary" fullWidth>
                Register
              </Button>
            </Link>
          </div>
        </DialogPanel>
      </div>
    </Dialog>
  );
}
