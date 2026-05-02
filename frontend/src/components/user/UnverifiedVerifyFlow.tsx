"use client";

import { useEffect } from "react";
import { useRouter } from "next/navigation";
import { Button } from "@/components/ui/Button";
import { VerificationCodeDisplay } from "@/components/user/VerificationCodeDisplay";
import { useCurrentUser } from "@/lib/user";

export function UnverifiedVerifyFlow() {
  const router = useRouter();
  const { data: user, refetch } = useCurrentUser({ refetchInterval: 5000 });

  useEffect(() => {
    if (user?.verified) {
      router.replace("/dashboard/overview");
    }
  }, [user?.verified, router]);

  return (
    <div className="flex flex-col items-center gap-8">
      <div className="text-center max-w-2xl">
        <p className="text-base text-fg-muted">
          To bid, list parcels for sale, or participate in auctions, you need to
          link your Second Life avatar to your SLPA account. This is a one-time
          verification that proves you control the avatar you claim to own.
        </p>
      </div>
      <div className="bg-bg-muted rounded-xl p-8 w-full max-w-2xl">
        <VerificationCodeDisplay />
      </div>
      <div className="text-center max-w-2xl space-y-2">
        <p className="text-sm font-semibold font-bold">How to verify:</p>
        <ol className="list-decimal list-inside text-sm text-fg-muted space-y-1">
          <li>Click &quot;Generate Verification Code&quot; above</li>
          <li>Copy the 6-digit code</li>
          <li>Go to any SLPA Verification Terminal in Second Life</li>
          <li>Touch the terminal and enter your code</li>
          <li>This page will automatically detect when you&apos;re verified</li>
        </ol>
      </div>
      <Button
        variant="tertiary"
        onClick={() => refetch()}
        className="text-fg-muted"
      >
        I&apos;ve entered the code in-world — refresh my status
      </Button>
    </div>
  );
}
