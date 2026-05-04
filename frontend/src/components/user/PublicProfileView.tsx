"use client";

import { useQuery } from "@tanstack/react-query";
import { notFound } from "next/navigation";
import { AlertCircle, BadgeCheck } from "@/components/ui/icons";
import { Avatar } from "@/components/ui/Avatar";
import { Card } from "@/components/ui/Card";
import { EmptyState } from "@/components/ui/EmptyState";
import { LoadingSpinner } from "@/components/ui/LoadingSpinner";
import { StatusBadge } from "@/components/ui/StatusBadge";
import { ProfileReviewTabs } from "@/components/reviews/ProfileReviewTabs";
import { isApiError } from "@/lib/api";
import { userApi } from "@/lib/user/api";
import { ActiveListingsSection } from "./ActiveListingsSection";
import { ReputationStars } from "./ReputationStars";
import { NewSellerBadge } from "./NewSellerBadge";
import { VerifiedIdentityCard } from "./VerifiedIdentityCard";

type PublicProfileViewProps = {
  userPublicId: string;
};

export function PublicProfileView({ userPublicId }: PublicProfileViewProps) {
  const { data: profile, isPending, isError, error } = useQuery({
    queryKey: ["publicProfile", userPublicId],
    queryFn: () => userApi.publicProfile(userPublicId),
    retry: (failureCount, err) => {
      if (isApiError(err) && err.status === 404) return false;
      return failureCount < 2;
    },
  });

  if (isPending) {
    return <LoadingSpinner label="Loading profile..." />;
  }

  if (isError) {
    if (isApiError(error) && error.status === 404) {
      notFound();
    }
    return (
      <EmptyState
        icon={AlertCircle}
        headline="Could not load profile"
      />
    );
  }

  return (
    <div className="mx-auto max-w-3xl px-4 py-12 flex flex-col gap-8">
      {/* Header */}
      <div className="flex items-start gap-6">
        <Avatar
          src={profile.profilePicUrl ?? undefined}
          alt={profile.displayName ?? "User"}
          name={profile.displayName ?? undefined}
          size="xl"
        />
        <div className="flex flex-col gap-2">
          <div className="flex items-center gap-3">
            <h1 className="text-xl font-bold tracking-tight font-display">
              {profile.displayName ?? "Anonymous"}
            </h1>
            {profile.verified ? (
              <StatusBadge tone="success">
                <BadgeCheck className="size-4" aria-hidden="true" /> Verified
              </StatusBadge>
            ) : (
              <StatusBadge tone="default">Unverified</StatusBadge>
            )}
          </div>
          {profile.bio && (
            <p className="text-sm text-fg-muted">{profile.bio}</p>
          )}
          <p className="text-xs text-fg-muted">
            Member since {new Date(profile.createdAt).toLocaleDateString()}
          </p>
        </div>
      </div>

      {/* SL Identity (only for verified users) */}
      {profile.verified && profile.slAvatarName && (
        <VerifiedIdentityCard user={profile} variant="public" />
      )}

      {/* Reputation */}
      <Card>
        <Card.Header>
          <h2 className="text-sm font-semibold tracking-tight">Reputation</h2>
        </Card.Header>
        <Card.Body>
          <div className="flex flex-col gap-4">
            <div className="flex flex-wrap gap-8">
              <ReputationStars
                rating={profile.avgSellerRating}
                reviewCount={profile.totalSellerReviews}
                label="Seller"
              />
              <ReputationStars
                rating={profile.avgBuyerRating}
                reviewCount={profile.totalBuyerReviews}
                label="Buyer"
              />
            </div>
            <div className="flex items-center gap-4">
              <NewSellerBadge completedSales={profile.completedSales} />
              <span className="text-xs text-fg-muted">
                {profile.completedSales} completed sale{profile.completedSales === 1 ? "" : "s"}
              </span>
            </div>
          </div>
        </Card.Body>
      </Card>

      {/* Reviews (Epic 08 sub-spec 1 §8.2) */}
      <Card>
        <Card.Header>
          <h2 className="text-sm font-semibold tracking-tight">Reviews</h2>
        </Card.Header>
        <Card.Body>
          <ProfileReviewTabs
            userPublicId={profile.publicId}
            avgSellerRating={profile.avgSellerRating}
            avgBuyerRating={profile.avgBuyerRating}
            totalSellerReviews={profile.totalSellerReviews}
            totalBuyerReviews={profile.totalBuyerReviews}
          />
        </Card.Body>
      </Card>

      {/* Active listings (Epic 04 sub-spec 2 §14) */}
      <Card>
        <Card.Header>
          <h2 className="text-sm font-semibold tracking-tight">Active listings</h2>
        </Card.Header>
        <Card.Body>
          <ActiveListingsSection userPublicId={profile.publicId} />
        </Card.Body>
      </Card>
    </div>
  );
}
