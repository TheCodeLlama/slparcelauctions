import Link from "next/link";
import { Avatar } from "@/components/ui/Avatar";
import { BadgeCheck } from "@/components/ui/icons";
import { cn } from "@/lib/cn";
import type { PublicUserProfile } from "@/lib/user/api";

export interface SellerHeaderProps {
  user: PublicUserProfile;
  className?: string;
}

/**
 * Compact profile strip at the top of /users/{id}/listings. Shows the
 * seller's avatar, display name, verification badge, member-since, and a
 * link back to the full profile page. Everything below is the standard
 * {@link BrowseShell} with {@code sellerId} pinned.
 */
export function SellerHeader({ user, className }: SellerHeaderProps) {
  const displayName = user.displayName ?? "Anonymous";
  const memberSince = new Date(user.createdAt).toLocaleDateString();
  return (
    <div
      className={cn(
        "flex items-center gap-4 border-b border-border-subtle px-6 py-4",
        className,
      )}
    >
      <Avatar
        src={user.profilePicUrl ?? undefined}
        alt={displayName}
        name={displayName}
        size="lg"
      />
      <div className="flex flex-col gap-1">
        <div className="flex items-center gap-2">
          <h1 className="text-base font-bold tracking-tight">
            {displayName}&rsquo;s listings
          </h1>
          {user.verified && (
            <BadgeCheck
              className="size-4 text-brand"
              aria-label="Verified"
            />
          )}
        </div>
        <p className="text-xs text-fg-muted">
          Member since {memberSince}
          <span aria-hidden="true"> · </span>
          <Link
            href={`/users/${user.publicId}`}
            className="text-brand hover:underline"
          >
            View full profile
          </Link>
        </p>
      </div>
    </div>
  );
}
