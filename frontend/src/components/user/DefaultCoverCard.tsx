"use client";

import { useCallback } from "react";
import { Card } from "@/components/ui/Card";
import { ImagePairField } from "@/components/ui/ImagePairField";
import { resizeImage } from "@/lib/image/resizeImage";
import {
  useCurrentUser,
  useDeleteDefaultCover,
  useUploadDefaultCover,
} from "@/lib/user";

/**
 * Settings card for the per-user default cover image. The cover is a
 * dual-slot pair: an independently uploadable / replaceable / deletable
 * "Light mode" and "Dark mode" image. A single theme-aware preview below
 * the pair renders whichever variant matches the visitor's active theme
 * (plan {@code 2026-05-21-theme-image-variants}).
 *
 * <p>No client-side size cap. Picked files are resized via
 * {@code browser-image-compression} (max 2048 px on the longest edge,
 * preserving aspect ratio + input MIME) before upload, so any phone-sized
 * source image becomes a small payload by the time it leaves the browser.
 */
export function DefaultCoverCard() {
  const { data: user } = useCurrentUser();
  const upload = useUploadDefaultCover();
  const remove = useDeleteDefaultCover();

  const handleUpload = useCallback(
    async (variant: "light" | "dark", file: File) => {
      const resized = await resizeImage(file, { maxDim: 2048 });
      upload.mutate({ variant, file: resized });
    },
    [upload],
  );

  const handleDelete = useCallback(
    (variant: "light" | "dark") => {
      remove.mutate({ variant });
    },
    [remove],
  );

  return (
    <Card>
      <Card.Header>
        <h2 className="text-sm font-semibold tracking-tight">
          Default Cover Image
        </h2>
      </Card.Header>
      <Card.Body>
        <ImagePairField
          surface="cover"
          testIdPrefix="default-cover"
          heading="Default cover"
          description="Auto-inserted as the first photo on every listing you create. Light and dark variants are optional - if you upload only one, it will be used in both themes. Existing listings are not affected."
          lightUrl={user?.defaultCoverLightUrl ?? null}
          darkUrl={user?.defaultCoverDarkUrl ?? null}
          altPrefix="Default cover"
          disabled={false}
          disabledTitle={undefined}
          slotClassName="aspect-[16/9] w-full rounded border border-border bg-bg-hover object-contain"
          emptyClassName="aspect-[16/9] w-full rounded border border-border bg-bg-hover"
          previewClassName="aspect-[16/9] w-full rounded border border-border bg-bg-hover object-contain"
          onUpload={handleUpload}
          onDelete={handleDelete}
          uploadBusyLight={
            upload.isPending && upload.variables?.variant === "light"
          }
          uploadBusyDark={
            upload.isPending && upload.variables?.variant === "dark"
          }
          deleteBusyLight={
            remove.isPending && remove.variables?.variant === "light"
          }
          deleteBusyDark={
            remove.isPending && remove.variables?.variant === "dark"
          }
        />
      </Card.Body>
    </Card>
  );
}
