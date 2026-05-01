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
  { id: "wallet", label: "Wallet", href: "/dashboard/wallet" },
];

export default function VerifiedDashboardLayout({ children }: { children: React.ReactNode }) {
  const router = useRouter();
  const { data: user, isPending, isError } = useCurrentUser();

  useEffect(() => {
    if (isPending || isError) return;
    if (!user?.verified) router.replace("/dashboard/verify");
  }, [isPending, isError, user?.verified, router]);

  if (isPending || !user) return <LoadingSpinner label="Loading your dashboard..." />;
  if (!user.verified) return <LoadingSpinner label="Redirecting..." />;

  return (
    <div className="mx-auto max-w-6xl px-4 py-8">
      <h1 className="text-headline-md font-display font-bold mb-6">Dashboard</h1>
      <Tabs tabs={TABS} className="mb-8" />
      {children}
    </div>
  );
}
