"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { useQueryClient } from "@tanstack/react-query";
import { Button } from "@/components/ui/Button";
import { LoadingSpinner } from "@/components/ui/LoadingSpinner";
import { useToast } from "@/components/ui/Toast";
import { onboardingApi } from "@/lib/user/api";
import { CURRENT_USER_KEY, useCurrentUser } from "@/lib/user";

/**
 * Display-name onboarding gate. Pre-fills the input with the user's
 * SLParcels username so the happy path is a one-click "Save". Skip /
 * Save with empty value both flip the flag without writing — the
 * username fallback in {@code User.getDisplayName()} surfaces the
 * username everywhere a name is rendered, so users who skip aren't
 * left nameless.
 */
export default function DisplayNameOnboardingPage() {
  const router = useRouter();
  const toast = useToast();
  const queryClient = useQueryClient();
  const { data: user, isPending } = useCurrentUser();
  const [value, setValue] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Field value: lazy-initialised from the loaded user so the input is
  // pre-filled before the user types. Stays driven by `setValue` after
  // the first user keystroke.
  const inputValue = value ?? user?.username ?? "";

  // Forward redirect if step is already done.
  useEffect(() => {
    if (isPending || !user) return;
    if (user.displayNameStepCompleted) {
      router.replace("/dashboard/overview");
    } else if (!user.avatarStepCompleted) {
      router.replace("/dashboard/avatar");
    }
  }, [isPending, user, router]);

  const finishStep = () => {
    queryClient.invalidateQueries({ queryKey: CURRENT_USER_KEY });
    router.push("/dashboard/overview");
  };

  const submit = async (displayName: string | null) => {
    setSubmitting(true);
    setError(null);
    try {
      await onboardingApi.setDisplayName(displayName);
      finishStep();
    } catch (e) {
      const message = e instanceof Error ? e.message : "Failed to save";
      setError(message);
      toast.error(message);
    } finally {
      setSubmitting(false);
    }
  };

  if (isPending || !user) return <LoadingSpinner label="Loading..." />;
  if (user.displayNameStepCompleted || !user.avatarStepCompleted) {
    return <LoadingSpinner label="Redirecting..." />;
  }

  return (
    <div className="mx-auto max-w-xl px-4 py-12">
      <h1 className="text-xl font-bold tracking-tight font-display text-center mb-2">
        Pick a display name
      </h1>
      <p className="text-center text-sm text-fg-muted mb-8">
        This is the name people will see in auctions and reviews. You can change it later in settings.
      </p>

      <label className="flex flex-col gap-2">
        <span className="text-xs font-medium text-fg-muted">Display name</span>
        <input
          type="text"
          maxLength={50}
          value={inputValue}
          onChange={(e) => setValue(e.target.value)}
          disabled={submitting}
          className="rounded-md border border-border bg-bg px-3 py-2 text-sm text-fg outline-none focus:border-brand"
          data-testid="display-name-input"
        />
      </label>

      {error && (
        <p className="mt-2 text-xs text-destructive" role="alert">
          {error}
        </p>
      )}

      <div className="mt-6 flex flex-col gap-3">
        <Button
          onClick={() => submit(inputValue.trim() === "" ? null : inputValue.trim())}
          disabled={submitting}
        >
          Save
        </Button>
        <Button variant="tertiary" onClick={() => submit(null)} disabled={submitting}>
          Skip
        </Button>
      </div>
    </div>
  );
}
