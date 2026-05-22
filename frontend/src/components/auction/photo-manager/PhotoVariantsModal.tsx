/* eslint-disable @next/next/no-img-element -- photo bytes are API-served
 * binary content; matches the AuctionHero / ImagePairField convention. */
"use client";

import { useCallback, useRef, useState, type ChangeEvent } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { Modal } from "@/components/ui/Modal";
import { Button } from "@/components/ui/Button";
import { UploadCloud } from "@/components/ui/icons";
import { apiUrl } from "@/lib/api/url";
import { isApiError } from "@/lib/api";
import { useToast } from "@/components/ui/Toast";
import {
  uploadPhotoDarkVariant,
  deletePhotoDarkVariant,
} from "@/lib/api/auctionPhotos";
import { resizeImage } from "@/lib/image/resizeImage";
import { auctionKey } from "@/hooks/useAuction";
import { activateAuctionKey } from "@/hooks/useActivateAuction";
import type { AuctionPhotoDto } from "@/types/auction";

const ACCEPTED_TYPES = new Set(["image/jpeg", "image/png", "image/webp"]);

export interface PhotoVariantsModalProps {
  open: boolean;
  onClose: () => void;
  auctionPublicId: string;
  /**
   * The sort-0 default-cover photo whose theme pair is being edited. Only
   * {@code USER_DEFAULT_COVER} / {@code GROUP_DEFAULT_COVER} rows are passed
   * here by the photo manager.
   */
  photo: AuctionPhotoDto;
}

/**
 * Modal for editing a default-cover photo's light/dark theme pair.
 *
 * <p>The <strong>light</strong> slot is read-only: it mirrors whatever the
 * seller's (or group's) persisted default cover already is, and the only way
 * to change it is to edit that default or delete the photo entirely. The
 * <strong>dark</strong> slot is editable inline — upload, replace, or delete
 * the dark variant without leaving the listing editor.
 *
 * <p>On a successful dark-variant mutation the parent auction query is
 * invalidated so the photo manager re-renders with the fresh photo set; the
 * modal also tracks the returned {@link AuctionPhotoDto} locally so its own
 * slots update immediately without waiting for the refetch.
 *
 * <p>Picked files are resized client-side (max 2048 px longest edge) before
 * upload, matching the {@code DefaultCoverCard} flow — there is no
 * client-side size cap on the picker.
 */
export function PhotoVariantsModal({
  open,
  onClose,
  auctionPublicId,
  photo,
}: PhotoVariantsModalProps) {
  const qc = useQueryClient();
  const toast = useToast();
  const fileInputRef = useRef<HTMLInputElement>(null);

  // Local view of the photo so the slots reflect a mutation result before
  // the parent's auction refetch resolves. Re-seeded from the prop whenever
  // a fresh photo identity comes in.
  const [current, setCurrent] = useState<AuctionPhotoDto>(photo);
  const photoId = current.publicId;
  if (photo.publicId !== photoId) {
    setCurrent(photo);
  }

  function refreshAuction() {
    qc.invalidateQueries({ queryKey: auctionKey(auctionPublicId) });
    qc.invalidateQueries({ queryKey: activateAuctionKey(auctionPublicId) });
  }

  function describeError(e: unknown, fallback: string): string {
    if (isApiError(e)) {
      return e.problem.detail ?? e.problem.title ?? fallback;
    }
    return e instanceof Error ? e.message : fallback;
  }

  const uploadMutation = useMutation({
    mutationFn: async (file: File) => {
      const resized = await resizeImage(file, { maxDim: 2048 });
      return uploadPhotoDarkVariant(auctionPublicId, photoId, resized);
    },
    onSuccess: (updated) => {
      setCurrent(updated);
      refreshAuction();
      toast.success("Dark variant saved.");
    },
    onError: (e) => {
      toast.error(describeError(e, "Could not upload the dark variant."));
    },
  });

  const deleteMutation = useMutation({
    mutationFn: () => deletePhotoDarkVariant(auctionPublicId, photoId),
    onSuccess: (updated) => {
      setCurrent(updated);
      refreshAuction();
      toast.success("Dark variant removed.");
    },
    onError: (e) => {
      toast.error(describeError(e, "Could not remove the dark variant."));
    },
  });

  const busy = uploadMutation.isPending || deleteMutation.isPending;

  const handlePick = useCallback(
    (e: ChangeEvent<HTMLInputElement>) => {
      const file = e.target.files?.[0];
      e.target.value = "";
      if (!file) return;
      if (!ACCEPTED_TYPES.has(file.type)) {
        toast.error("Pick a JPEG, PNG, or WebP image.");
        return;
      }
      uploadMutation.mutate(file);
    },
    [uploadMutation, toast],
  );

  const lightSrc = apiUrl(current.lightUrl) ?? undefined;
  const darkSrc = current.darkUrl
    ? apiUrl(current.darkUrl) ?? undefined
    : undefined;

  return (
    <Modal
      open={open}
      onClose={onClose}
      title="Cover photo: light & dark"
      footer={
        <Button
          type="button"
          variant="secondary"
          onClick={onClose}
          data-testid="photo-variants-modal-close"
        >
          Done
        </Button>
      }
    >
      <div className="flex flex-col gap-5" data-testid="photo-variants-modal">
        <p className="text-xs text-fg-muted">
          This cover comes from your default cover image. The light version is
          fixed here; add a dark version so the listing looks right for
          visitors browsing in dark mode.
        </p>

        {/* Light slot — read-only. */}
        <section
          className="flex flex-col gap-2"
          data-testid="photo-variants-light-slot"
        >
          <span className="text-[11px] font-medium uppercase tracking-wide text-fg-subtle">
            Light mode
          </span>
          <img
            src={lightSrc}
            alt="Cover photo (light mode)"
            className="aspect-[16/9] w-full rounded border border-border bg-bg-hover object-contain"
            data-testid="photo-variants-light-image"
          />
          <p
            className="text-[11px] text-fg-subtle"
            data-testid="photo-variants-light-note"
          >
            Edit your profile default cover to change this. Or delete the photo
            and upload a new one to start fresh.
          </p>
        </section>

        {/* Dark slot — editable. */}
        <section
          className="flex flex-col gap-2"
          data-testid="photo-variants-dark-slot"
        >
          <span className="text-[11px] font-medium uppercase tracking-wide text-fg-subtle">
            Dark mode
          </span>
          {current.darkUrl ? (
            <>
              <img
                src={darkSrc}
                alt="Cover photo (dark mode)"
                className="aspect-[16/9] w-full rounded border border-border bg-bg-hover object-contain"
                data-testid="photo-variants-dark-image"
              />
              <div className="flex flex-wrap items-center gap-2">
                <Button
                  type="button"
                  variant="secondary"
                  size="sm"
                  onClick={() => fileInputRef.current?.click()}
                  disabled={busy}
                  loading={uploadMutation.isPending}
                  data-testid="photo-variants-dark-replace"
                >
                  Replace
                </Button>
                <Button
                  type="button"
                  variant="tertiary"
                  size="sm"
                  onClick={() => deleteMutation.mutate()}
                  disabled={busy}
                  loading={deleteMutation.isPending}
                  data-testid="photo-variants-dark-delete"
                >
                  Remove
                </Button>
              </div>
            </>
          ) : (
            <button
              type="button"
              onClick={() => fileInputRef.current?.click()}
              disabled={busy}
              className="flex aspect-[16/9] w-full flex-col items-center justify-center gap-2 rounded border-2 border-dashed border-border-subtle text-fg-muted hover:bg-bg-hover disabled:opacity-50 focus:outline-none focus-visible:ring-2 focus-visible:ring-brand"
              data-testid="photo-variants-dark-dropzone"
            >
              <UploadCloud className="size-6" aria-hidden="true" />
              <span className="text-xs">
                {uploadMutation.isPending
                  ? "Uploading…"
                  : "Upload a dark-mode version"}
              </span>
            </button>
          )}
        </section>

        <input
          ref={fileInputRef}
          type="file"
          accept="image/jpeg,image/png,image/webp"
          className="hidden"
          aria-label="Upload dark mode cover photo"
          onChange={handlePick}
          data-testid="photo-variants-dark-input"
        />
      </div>
    </Modal>
  );
}
