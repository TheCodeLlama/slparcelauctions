"use client";

import { useState } from "react";
import { Button } from "@/components/ui/Button";
import { CodeDisplay } from "@/components/ui/CodeDisplay";
import { CountdownTimer } from "@/components/ui/CountdownTimer";
import { LoadingSpinner } from "@/components/ui/LoadingSpinner";
import { useToast } from "@/components/ui/Toast";
import {
  useActiveVerificationCode,
  useGenerateVerificationCode,
} from "@/lib/user";

export function VerificationCodeDisplay() {
  const { data: activeCode, isPending } = useActiveVerificationCode();
  const generateMutation = useGenerateVerificationCode();
  const toast = useToast();
  const [showRegenerateConfirm, setShowRegenerateConfirm] = useState(false);

  if (isPending) return <LoadingSpinner label="Checking for active code..." />;

  if (!activeCode) {
    return (
      <div className="flex flex-col items-center gap-6 py-8">
        <p className="text-body-lg text-on-surface-variant text-center max-w-md">
          Click the button below to generate a 6-digit verification code.
          You&apos;ll have 15 minutes to enter it at any SLPA Verification
          Terminal in Second Life.
        </p>
        <Button
          onClick={() => generateMutation.mutate()}
          disabled={generateMutation.isPending}
          size="lg"
        >
          {generateMutation.isPending
            ? "Generating..."
            : "Generate Verification Code"}
        </Button>
      </div>
    );
  }

  const expiresAt = new Date(activeCode.expiresAt);

  return (
    <div className="flex flex-col items-center gap-6 py-8">
      <CodeDisplay
        code={activeCode.code}
        label="Enter this code at any SLPA Verification Terminal"
        onCopySuccess={() => toast.success("Code copied to clipboard")}
        onCopyError={() => toast.error("Failed to copy — copy the code manually")}
      />
      <div className="flex items-center gap-2 text-on-surface-variant">
        <span className="text-body-sm">Expires in</span>
        <CountdownTimer expiresAt={expiresAt} />
      </div>
      {!showRegenerateConfirm ? (
        <Button
          variant="secondary"
          onClick={() => setShowRegenerateConfirm(true)}
          disabled={generateMutation.isPending}
        >
          Regenerate Code
        </Button>
      ) : (
        <div className="flex flex-col items-center gap-3">
          <p className="text-body-sm text-on-surface-variant">
            This will invalidate the current code. Are you sure?
          </p>
          <div className="flex gap-2">
            <Button
              variant="tertiary"
              onClick={() => setShowRegenerateConfirm(false)}
            >
              Cancel
            </Button>
            <Button
              variant="secondary"
              onClick={() => {
                setShowRegenerateConfirm(false);
                generateMutation.mutate();
              }}
            >
              Yes, regenerate
            </Button>
          </div>
        </div>
      )}
    </div>
  );
}
