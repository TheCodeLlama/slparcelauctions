"use client";

import { LoadingSpinner } from "@/components/ui/LoadingSpinner";
import { useCurrentUser } from "@/lib/user";
import { VerifiedIdentityCard } from "./VerifiedIdentityCard";
import { ProfilePictureUploader } from "./ProfilePictureUploader";
import { ProfileEditForm } from "./ProfileEditForm";

export function VerifiedOverview() {
  const { data: user, isPending } = useCurrentUser();

  if (isPending || !user) return <LoadingSpinner label="Loading profile..." />;

  return (
    <div className="grid gap-8 md:grid-cols-2">
      <div className="flex flex-col gap-6">
        <VerifiedIdentityCard user={user} variant="dashboard" />
        <ProfilePictureUploader user={user} />
      </div>
      <div>
        <ProfileEditForm user={user} />
      </div>
    </div>
  );
}
