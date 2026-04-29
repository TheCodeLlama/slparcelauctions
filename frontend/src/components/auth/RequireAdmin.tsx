"use client";

import { useEffect, type ReactNode } from "react";
import { useRouter } from "next/navigation";
import { useAuth } from "@/lib/auth";

type RequireAdminProps = {
  children: ReactNode;
};

export function RequireAdmin({ children }: RequireAdminProps) {
  const session = useAuth();
  const router = useRouter();

  useEffect(() => {
    if (session.status === "unauthenticated") {
      router.replace("/");
    } else if (session.status === "authenticated" && session.user.role !== "ADMIN") {
      router.replace("/");
    }
  }, [session, router]);

  if (session.status === "loading") {
    return (
      <div className="flex min-h-[60vh] items-center justify-center">
        <div className="h-8 w-8 animate-spin rounded-full border-4 border-primary border-t-transparent" />
      </div>
    );
  }

  if (session.status !== "authenticated" || session.user.role !== "ADMIN") {
    return null;
  }

  return <>{children}</>;
}
