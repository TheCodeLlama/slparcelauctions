"use client";

import { useCallback, useRef, type ChangeEvent } from "react";
import { Button } from "@/components/ui/Button";
import { Card } from "@/components/ui/Card";
import { resizeImage } from "@/lib/image/resizeImage";
import { apiUrl } from "@/lib/api/url";
import {
  useCurrentUser,
  useDeleteDefaultCover,
  useUploadDefaultCover,
} from "@/lib/user";

const ACCEPTED_TYPES = new Set(["image/jpeg", "image/png", "image/webp"]);

/**
 * Settings card for the per-user default cover image. Three render states:
 *
 * <ul>
 *   <li><b>Empty</b> — no cover set; "Choose image" button kicks off the
 *       file picker.</li>
 *   <li><b>Uploading</b> — disabled card with spinner during the multipart
 *       PUT.</li>
 *   <li><b>Set</b> — thumbnail preview + Replace + Remove buttons.</li>
 * </ul>
 *
 * <p>No client-side size cap. Picked files are resized via
 * {@code browser-image-compression} (max 2048 px on the longest edge,
 * preserving aspect ratio + input MIME) before the PUT, so any phone-sized
 * source image becomes a small payload by the time it leaves the browser.
 */
export function DefaultCoverCard() {
  const { data: user } = useCurrentUser();
  const upload = useUploadDefaultCover();
  const remove = useDeleteDefaultCover();
  const fileInputRef = useRef<HTMLInputElement>(null);

  const handlePick = useCallback(
    async (e: ChangeEvent<HTMLInputElement>) => {
      const file = e.target.files?.[0];
      // Reset input value so selecting the same file again still triggers
      // onChange — the React fixup folks fall in this trap a lot.
      e.target.value = "";
      if (!file) return;
      if (!ACCEPTED_TYPES.has(file.type)) return;
      const resized = await resizeImage(file, { maxDim: 2048 });
      upload.mutate(resized);
    },
    [upload],
  );

  const busy = upload.isPending || remove.isPending;
  const hasCover = !!user?.defaultCoverUrl;

  return (
    <Card>
      <Card.Header>
        <h2 className="text-sm font-semibold tracking-tight">
          Default Cover Image
        </h2>
      </Card.Header>
      <Card.Body>
        <p className="mb-4 text-xs text-fg-muted">
          Used as the first photo of every new listing you create. Existing
          listings are not affected.
        </p>

        {hasCover ? (
          <div className="flex flex-col gap-3">
            <img
              src={apiUrl(user!.defaultCoverUrl) ?? undefined}
              alt="Default cover"
              className="rounded max-w-full"
            />
            <div className="flex gap-2">
              <Button
                variant="secondary"
                onClick={() => fileInputRef.current?.click()}
                disabled={busy}
              >
                Replace
              </Button>
              <Button
                variant="destructive"
                onClick={() => remove.mutate()}
                disabled={busy}
              >
                Remove
              </Button>
            </div>
          </div>
        ) : (
          <Button
            variant="primary"
            onClick={() => fileInputRef.current?.click()}
            loading={busy}
            disabled={busy}
          >
            Choose image
          </Button>
        )}

        <input
          ref={fileInputRef}
          type="file"
          accept="image/jpeg,image/png,image/webp"
          className="hidden"
          aria-label="Upload default cover"
          onChange={handlePick}
          data-testid="default-cover-file-input"
        />
      </Card.Body>
    </Card>
  );
}
