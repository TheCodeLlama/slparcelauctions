"use client";

import { useRouter } from "next/navigation";
import { useEffect } from "react";
import { LoadingSpinner } from "@/components/ui/LoadingSpinner";
import { useCurrentUser } from "@/lib/user";

/**
 * Verify-gate only. Tabs / dashboard chrome moved into the nested
 * (onboarded) layout so the new forced-onboarding pages
 * (/dashboard/avatar, /dashboard/display-name) — which are siblings of
 * (onboarded) under (verified) — can render their own page chrome
 * without inheriting the dashboard tabs strip.
 */
export default function VerifiedDashboardLayout({ children }: { children: React.ReactNode }) {
  const router = useRouter();
  const { data: user, isPending, isError } = useCurrentUser();

  useEffect(() => {
    if (isPending || isError) return;
    if (!user?.verified) router.replace("/dashboard/verify");
  }, [isPending, isError, user?.verified, router]);

  if (isPending || !user) return <LoadingSpinner label="Loading your dashboard..." />;
  if (!user.verified) return <LoadingSpinner label="Redirecting..." />;

  return <>{children}</>;
}
