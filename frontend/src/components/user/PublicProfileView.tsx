"use client";

import { useQuery } from "@tanstack/react-query";
import { notFound } from "next/navigation";
import { AlertCircle, BadgeCheck, Gavel, MessageSquare } from "@/components/ui/icons";
import { Avatar } from "@/components/ui/Avatar";
import { Card } from "@/components/ui/Card";
import { EmptyState } from "@/components/ui/EmptyState";
import { LoadingSpinner } from "@/components/ui/LoadingSpinner";
import { StatusBadge } from "@/components/ui/StatusBadge";
import { isApiError } from "@/lib/api";
import { userApi } from "@/lib/user/api";
import { ReputationStars } from "./ReputationStars";
import { NewSellerBadge } from "./NewSellerBadge";
import { VerifiedIdentityCard } from "./VerifiedIdentityCard";

type PublicProfileViewProps = {
  userId: number;
};

export function PublicProfileView({ userId }: PublicProfileViewProps) {
  const { data: profile, isPending, isError, error } = useQuery({
    queryKey: ["publicProfile", userId],
    queryFn: () => userApi.publicProfile(userId),
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
            <h1 className="text-headline-md font-display font-bold">
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
            <p className="text-body-md text-on-surface-variant">{profile.bio}</p>
          )}
          <p className="text-body-sm text-on-surface-variant">
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
          <h2 className="text-title-md font-bold">Reputation</h2>
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
              <span className="text-body-sm text-on-surface-variant">
                {profile.completedSales} completed sale{profile.completedSales === 1 ? "" : "s"}
              </span>
            </div>
          </div>
        </Card.Body>
      </Card>

      {/* Placeholder: Reviews */}
      <Card>
        <Card.Header>
          <h2 className="text-title-md font-bold">Reviews</h2>
        </Card.Header>
        <Card.Body>
          <EmptyState
            icon={MessageSquare}
            headline="No reviews yet"
            description="Reviews will appear here once this user completes transactions."
          />
        </Card.Body>
      </Card>

      {/* Placeholder: Listings */}
      <Card>
        <Card.Header>
          <h2 className="text-title-md font-bold">Listings</h2>
        </Card.Header>
        <Card.Body>
          <EmptyState
            icon={Gavel}
            headline="No listings yet"
            description="Active and past auction listings will appear here."
          />
        </Card.Body>
      </Card>
    </div>
  );
}
