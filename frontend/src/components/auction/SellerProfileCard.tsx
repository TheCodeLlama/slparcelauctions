import Link from "next/link";
import { ArrowRight } from "@/components/ui/icons";
import { Avatar } from "@/components/ui/Avatar";
import { NewSellerBadge } from "@/components/user/NewSellerBadge";
import { ReputationStars } from "@/components/user/ReputationStars";
import { cn } from "@/lib/cn";

/**
 * Seller summary card for the auction detail page.
 *
 * The public auction DTO currently exposes only {@code sellerId} — Task 9
 * adds a server-side enrichment step that inlines the seller's public
 * profile so this card can render without an extra client round-trip. For
 * now, callers fetch the profile themselves (via {@code userApi.publicProfile})
 * and hand the shape below in. Once enrichment ships, {@code auction.seller}
 * will have exactly this shape so the prop signature stays stable.
 *
 * {@link NewSellerBadge} self-hides when completedSales ≥ 3 — we do not
 * need to branch here.
 */
export interface SellerProfileCardSeller {
  id: number;
  displayName: string;
  slAvatarName?: string | null;
  profilePicUrl?: string | null;
  avgSellerRating?: number | null;
  totalSellerReviews?: number | null;
  completedSales?: number | null;
}

interface Props {
  seller: SellerProfileCardSeller;
  className?: string;
}

export function SellerProfileCard({ seller, className }: Props) {
  const completedSales = seller.completedSales ?? 0;
  const totalReviews = seller.totalSellerReviews ?? 0;
  const rating = seller.avgSellerRating ?? null;

  return (
    <section
      aria-label="Seller"
      className={cn(
        "rounded-default bg-surface-container-lowest p-6 flex flex-col gap-4",
        className,
      )}
      data-testid="seller-profile-card"
    >
      <header className="flex items-start gap-4">
        <Avatar
          src={seller.profilePicUrl ?? undefined}
          alt={seller.displayName}
          name={seller.displayName}
          size="lg"
        />
        <div className="flex flex-col gap-0.5 min-w-0">
          <h2 className="text-title-lg font-bold text-on-surface truncate">
            {seller.displayName}
          </h2>
          {seller.slAvatarName && (
            <p
              className="text-label-sm text-on-surface-variant truncate"
              data-testid="seller-profile-card-sl-name"
            >
              {seller.slAvatarName}
            </p>
          )}
        </div>
      </header>

      <div className="flex flex-col gap-2">
        <ReputationStars rating={rating} reviewCount={totalReviews} />
        <p className="text-body-sm text-on-surface-variant">
          {completedSales} completed sale{completedSales === 1 ? "" : "s"}
        </p>
        <NewSellerBadge completedSales={completedSales} />
      </div>

      <Link
        href={`/users/${seller.id}`}
        className="inline-flex items-center gap-1 text-primary text-label-lg font-medium hover:underline underline-offset-4 self-start"
        data-testid="seller-profile-card-link"
      >
        View profile
        <ArrowRight className="size-4" aria-hidden="true" />
      </Link>
    </section>
  );
}
