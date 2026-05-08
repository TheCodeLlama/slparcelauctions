"use client";

import { useRouter } from "next/navigation";
import { useEffect } from "react";
import { LoadingSpinner } from "@/components/ui/LoadingSpinner";
import { Tabs, type TabItem } from "@/components/ui/Tabs";
import { useCurrentUser } from "@/lib/user";

const TABS: TabItem[] = [
  { id: "overview", label: "Overview", href: "/dashboard/overview" },
  { id: "bids", label: "My Bids", href: "/dashboard/bids" },
  { id: "listings", label: "My Listings", href: "/dashboard/listings" },
];

/**
 * Onboarded-gate layout. Mounts only for verified users (the parent
 * (verified) layout has already enforced that). Redirects to the
 * forced-onboarding gate pages until both flags are true. Renders the
 * dashboard chrome (h1 + tabs + page container) once the user is fully
 * onboarded.
 */
export default function OnboardedLayout({ children }: { children: React.ReactNode }) {
  const router = useRouter();
  const { data: user, isPending, isError } = useCurrentUser();

  useEffect(() => {
    if (isPending || isError || !user) return;
    if (!user.avatarStepCompleted) {
      router.replace("/dashboard/avatar");
      return;
    }
    if (!user.displayNameStepCompleted) {
      router.replace("/dashboard/display-name");
    }
  }, [isPending, isError, user, router]);

  if (isPending || !user) return <LoadingSpinner label="Loading your dashboard..." />;
  if (!user.avatarStepCompleted || !user.displayNameStepCompleted) {
    return <LoadingSpinner label="Redirecting..." />;
  }

  return (
    <div className="mx-auto max-w-6xl px-4 py-8">
      <h1 className="text-xl font-bold tracking-tight font-display mb-6">Dashboard</h1>
      <Tabs tabs={TABS} className="mb-8" />
      {children}
    </div>
  );
}
