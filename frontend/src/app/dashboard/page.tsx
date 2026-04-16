"use client";

import { useRouter } from "next/navigation";
import { useEffect } from "react";
import { useCurrentUser } from "@/lib/user";
import { LoadingSpinner } from "@/components/ui/LoadingSpinner";

export default function DashboardIndex() {
  const router = useRouter();
  const { data: user, isPending, isError } = useCurrentUser();

  useEffect(() => {
    if (isPending || isError) return;
    if (user?.verified) {
      router.replace("/dashboard/overview");
    } else {
      router.replace("/dashboard/verify");
    }
  }, [isPending, isError, user?.verified, router]);

  return <LoadingSpinner label="Loading your dashboard..." />;
}
