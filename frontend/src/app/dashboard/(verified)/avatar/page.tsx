"use client";

import { useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { useQueryClient } from "@tanstack/react-query";
import { AvatarCropper } from "@/components/user/AvatarCropper";
import { Button } from "@/components/ui/Button";
import { LoadingSpinner } from "@/components/ui/LoadingSpinner";
import { useToast } from "@/components/ui/Toast";
import { onboardingApi, userApi } from "@/lib/user/api";
import { CURRENT_USER_KEY, useCurrentUser } from "@/lib/user";

type SourceState =
  | { kind: "loading" }
  | { kind: "no-photo" }
  | { kind: "ready"; objectUrl: string };

/**
 * Avatar onboarding gate (post-verify). Three exits:
 *
 * <ol>
 *   <li>Save the cropped SL profile photo (or uploaded image) → existing
 *       upload endpoint flips the flag.</li>
 *   <li>Skip → dedicated /onboarding/avatar/skip endpoint flips the flag
 *       without writing a profile pic URL.</li>
 *   <li>Upload your own → file picker swaps the cropper source.</li>
 * </ol>
 *
 * <p>Self-redirects forward when the user lands here after already
 * completing the step (bookmark, back button).
 */
export default function AvatarOnboardingPage() {
  const router = useRouter();
  const toast = useToast();
  const queryClient = useQueryClient();
  const { data: user, isPending } = useCurrentUser();
  const [source, setSource] = useState<SourceState>({ kind: "loading" });
  const [saving, setSaving] = useState(false);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const objectUrlRef = useRef<string | null>(null);

  // Forward redirect if step is already done.
  useEffect(() => {
    if (isPending || !user) return;
    if (user.avatarStepCompleted) {
      router.replace(
        user.displayNameStepCompleted ? "/dashboard/overview" : "/dashboard/display-name",
      );
    }
  }, [isPending, user, router]);

  // Initial SL profile-photo fetch.
  useEffect(() => {
    if (isPending || !user || user.avatarStepCompleted) return;
    let cancelled = false;
    (async () => {
      try {
        const blob = await onboardingApi.fetchSlProfilePhoto();
        if (cancelled) return;
        if (blob == null) {
          setSource({ kind: "no-photo" });
          return;
        }
        const url = URL.createObjectURL(blob);
        objectUrlRef.current = url;
        setSource({ kind: "ready", objectUrl: url });
      } catch {
        if (!cancelled) setSource({ kind: "no-photo" });
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [isPending, user]);

  // Revoke object URLs on unmount.
  useEffect(() => {
    return () => {
      if (objectUrlRef.current) URL.revokeObjectURL(objectUrlRef.current);
    };
  }, []);

  const navigateForward = () => {
    queryClient.invalidateQueries({ queryKey: CURRENT_USER_KEY });
    router.push("/dashboard/display-name");
  };

  const handleSave = async (blob: Blob) => {
    setSaving(true);
    try {
      await userApi.uploadAvatarBlob(blob);
      toast.success("Avatar saved");
      navigateForward();
    } catch {
      toast.error("Failed to save avatar");
    } finally {
      setSaving(false);
    }
  };

  const handleSkip = async () => {
    setSaving(true);
    try {
      await onboardingApi.skipAvatar();
      navigateForward();
    } catch {
      toast.error("Failed to skip");
    } finally {
      setSaving(false);
    }
  };

  const handleFilePick = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    if (objectUrlRef.current) URL.revokeObjectURL(objectUrlRef.current);
    const url = URL.createObjectURL(file);
    objectUrlRef.current = url;
    setSource({ kind: "ready", objectUrl: url });
  };

  if (isPending || !user) {
    return <LoadingSpinner label="Loading..." />;
  }
  if (user.avatarStepCompleted) {
    return <LoadingSpinner label="Redirecting..." />;
  }

  return (
    <div className="mx-auto max-w-xl px-4 py-12">
      <h1 className="text-xl font-bold tracking-tight font-display text-center mb-2">
        Choose your avatar
      </h1>
      <p className="text-center text-sm text-fg-muted mb-8">
        Pick a photo people will see next to your auctions and bids.
      </p>

      {source.kind === "loading" && <LoadingSpinner label="Loading your SL photo..." />}

      {source.kind === "ready" && (
        <AvatarCropper
          imageSrc={source.objectUrl}
          onSave={handleSave}
          saveLabel="Save this avatar"
        />
      )}

      {source.kind === "no-photo" && (
        <div className="rounded-md bg-bg-subtle p-6 text-center mb-6">
          <p className="text-sm text-fg-muted">
            We couldn&apos;t find a profile photo on your Second Life profile.
            Upload one below or skip for now.
          </p>
        </div>
      )}

      <div className="mt-6 flex flex-col gap-3">
        <input
          ref={fileInputRef}
          type="file"
          accept="image/jpeg,image/png,image/webp"
          className="hidden"
          onChange={handleFilePick}
          data-testid="avatar-file-input"
        />
        <Button
          variant="secondary"
          onClick={() => fileInputRef.current?.click()}
          disabled={saving}
        >
          {source.kind === "ready" ? "Upload a different image" : "Upload an image"}
        </Button>
        <Button variant="tertiary" onClick={handleSkip} disabled={saving}>
          Skip — no avatar
        </Button>
      </div>
    </div>
  );
}
