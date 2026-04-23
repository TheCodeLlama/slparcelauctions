"use client";

import { useRouter } from "next/navigation";
import { useEffect } from "react";
import { RequireAuth } from "@/components/auth/RequireAuth";
import { LoadingSpinner } from "@/components/ui/LoadingSpinner";
import { useCurrentUser } from "@/lib/user";

/**
 * Shared layout for the authenticated+verified listing routes:
 *   - /listings/create
 *   - /listings/[id]/edit
 *   - /listings/[id]/activate (Task 9)
 *
 * Mirrors the dashboard/(verified) guard: RequireAuth handles the
 * unauthenticated redirect to /login, and a useCurrentUser check
 * bounces unverified users to /dashboard/verify (the single place
 * the verification flow lives). Using the Next.js route group `()`
 * keeps URLs stable — /listings/create still resolves.
 */
export default function VerifiedListingsLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <RequireAuth>
      <VerifiedGate>{children}</VerifiedGate>
    </RequireAuth>
  );
}

function VerifiedGate({ children }: { children: React.ReactNode }) {
  const router = useRouter();
  const { data: user, isPending, isError } = useCurrentUser();

  useEffect(() => {
    if (isPending || isError) return;
    if (!user?.verified) router.replace("/dashboard/verify");
  }, [isPending, isError, user?.verified, router]);

  if (isPending || !user) return <LoadingSpinner label="Loading..." />;
  if (!user.verified) return <LoadingSpinner label="Redirecting..." />;

  return <>{children}</>;
}
