import type { ReactNode } from "react";
import Link from "next/link";
import { ChevronLeft } from "@/components/ui/icons";

export interface EscrowPageLayoutProps {
  auctionPublicId: string;
  children: ReactNode;
}

/**
 * Page container for /auction/[publicId]/escrow. Holds a "Back to auction"
 * crumb and a constrained content column — the per-state cards and header
 * live inside the {@code space-y-6} stack. Kept role-agnostic so seller and
 * winner views share the same frame.
 */
export function EscrowPageLayout({ auctionPublicId, children }: EscrowPageLayoutProps) {
  return (
    <main className="mx-auto max-w-2xl px-4 py-8">
      <Link
        href={`/auction/${auctionPublicId}`}
        className="inline-flex items-center gap-1 text-xs font-medium text-fg-muted hover:text-fg"
      >
        <ChevronLeft className="size-4" aria-hidden="true" />
        Back to auction
      </Link>
      <div className="mt-6 space-y-6">{children}</div>
    </main>
  );
}
