"use client";

import { UnverifiedVerifyFlow } from "@/components/user/UnverifiedVerifyFlow";

export default function VerifyPage() {
  return (
    <div className="mx-auto max-w-5xl px-4 py-12">
      <h1 className="text-headline-md font-display font-bold text-center mb-8">
        Verify Your Second Life Avatar
      </h1>
      <UnverifiedVerifyFlow />
    </div>
  );
}
