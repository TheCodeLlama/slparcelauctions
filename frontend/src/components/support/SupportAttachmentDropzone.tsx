"use client";

import { useEffect, useRef, useState, type ChangeEvent } from "react";
import { UploadCloud, X } from "@/components/ui/icons";
import { useUploadSupportAttachment } from "@/hooks/useUploadSupportAttachment";
import { isApiError } from "@/lib/api";
import type { SupportTicketErrorCode } from "@/types/support";

const DEFAULT_MAX_ATTACHMENTS = 3;
const ACCEPT_MIME = "image/png,image/jpeg,image/webp,image/gif";

type Props = {
  attachmentKeys: string[];
  onAttachmentKeyAdded: (key: string) => void;
  onAttachmentKeyRemoved: (key: string) => void;
  maxAttachments?: number;
  disabled?: boolean;
};

/**
 * Reusable image-attachment surface used by the new-ticket form and the
 * thread reply composers (user + admin). The parent owns the list of
 * attachment keys; this component handles upload, preview, and remove
 * mechanics only.
 *
 * Removal does NOT delete from S3 — the staging-bucket lifecycle rule
 * sweeps unreferenced objects after 24h. We simply drop the key from
 * the parent's pending list.
 */
function describeUploadError(err: unknown): string {
  if (!isApiError(err)) {
    return "Could not upload image. Try again.";
  }
  const code = (err.problem as { code?: SupportTicketErrorCode }).code;
  if (code === "INVALID_ATTACHMENT") {
    return "Image is too large or unsupported format.";
  }
  return "Could not upload image. Try again.";
}

export function SupportAttachmentDropzone({
  attachmentKeys,
  onAttachmentKeyAdded,
  onAttachmentKeyRemoved,
  maxAttachments = DEFAULT_MAX_ATTACHMENTS,
  disabled = false,
}: Props) {
  const inputRef = useRef<HTMLInputElement>(null);
  const upload = useUploadSupportAttachment();
  const [error, setError] = useState<string | null>(null);

  // Maps a successfully-uploaded attachmentKey to the local object URL we
  // built from the picked File. Lives only for the lifetime of this
  // mounted component — once the form submits and the page navigates,
  // these URLs are revoked.
  const [previewUrls, setPreviewUrls] = useState<Map<string, string>>(
    () => new Map(),
  );

  // Clean up any outstanding object URLs on unmount.
  useEffect(() => {
    return () => {
      previewUrls.forEach((url) => URL.revokeObjectURL(url));
    };
    // We intentionally don't include `previewUrls` in deps — we only want
    // to revoke on unmount. Per-key revoke happens in onAttachmentKeyRemoved.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const atMax = attachmentKeys.length >= maxAttachments;
  const showDropzone = !atMax;

  async function handleFile(file: File) {
    setError(null);
    try {
      const { attachmentKey } = await upload.mutateAsync(file);
      const url = URL.createObjectURL(file);
      setPreviewUrls((prev) => {
        const next = new Map(prev);
        next.set(attachmentKey, url);
        return next;
      });
      onAttachmentKeyAdded(attachmentKey);
    } catch (e) {
      setError(describeUploadError(e));
    }
  }

  function handleInputChange(e: ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0];
    if (file) {
      void handleFile(file);
    }
    // Reset so picking the same file again still fires onChange.
    e.target.value = "";
  }

  function handleRemove(key: string) {
    if (disabled) return;
    setPreviewUrls((prev) => {
      const next = new Map(prev);
      const url = next.get(key);
      if (url) URL.revokeObjectURL(url);
      next.delete(key);
      return next;
    });
    onAttachmentKeyRemoved(key);
  }

  function openPicker() {
    if (disabled || atMax) return;
    inputRef.current?.click();
  }

  return (
    <div
      className="flex flex-col gap-2"
      data-testid="support-attachment-dropzone"
    >
      {attachmentKeys.length > 0 && (
        <ul
          className="flex flex-wrap gap-2"
          data-testid="support-attachment-thumbnails"
        >
          {attachmentKeys.map((key) => {
            const url = previewUrls.get(key);
            return (
              <li
                key={key}
                data-testid={`support-attachment-thumb-${key}`}
                className="relative size-20 overflow-hidden rounded-lg ring-1 ring-border-subtle bg-bg-subtle"
              >
                {url ? (
                  // The preview src is a local object URL, not a backend
                  // path, so it does NOT need to flow through apiUrl().
                  // eslint-disable-next-line @next/next/no-img-element
                  <img
                    src={url}
                    alt="Attachment preview"
                    className="size-full object-cover"
                  />
                ) : (
                  <div className="flex size-full items-center justify-center text-[10px] text-fg-muted">
                    Attached
                  </div>
                )}
                <button
                  type="button"
                  onClick={() => handleRemove(key)}
                  disabled={disabled}
                  aria-label="Remove attachment"
                  data-testid={`support-attachment-remove-${key}`}
                  className="absolute right-1 top-1 inline-flex size-5 items-center justify-center rounded-full bg-fg/70 text-bg hover:bg-fg disabled:opacity-50 disabled:pointer-events-none"
                >
                  <X className="size-3" />
                </button>
              </li>
            );
          })}
        </ul>
      )}

      {showDropzone && (
        <div
          data-testid="support-attachment-zone"
          role="button"
          tabIndex={disabled ? -1 : 0}
          aria-disabled={disabled}
          onClick={openPicker}
          onKeyDown={(e) => {
            if (disabled) return;
            if (e.key === "Enter" || e.key === " ") {
              e.preventDefault();
              openPicker();
            }
          }}
          className={
            "flex cursor-pointer flex-col items-center justify-center gap-2 rounded-lg border-2 border-dashed border-border-subtle p-4 text-center text-xs text-fg-muted transition-colors hover:border-brand focus:outline-none focus-visible:ring-2 focus-visible:ring-brand " +
            (disabled ? "cursor-not-allowed opacity-60" : "")
          }
        >
          <UploadCloud className="size-5" aria-hidden="true" />
          <span>
            {upload.isPending
              ? "Uploading..."
              : "Drop image or click to add"}
          </span>
          <span className="text-[10px] text-fg-muted">
            PNG, JPEG, WebP, or GIF. Up to {maxAttachments} images.
          </span>
        </div>
      )}

      <input
        ref={inputRef}
        type="file"
        accept={ACCEPT_MIME}
        className="sr-only"
        data-testid="support-attachment-input"
        disabled={disabled}
        onChange={handleInputChange}
      />

      {error && (
        <p
          role="alert"
          data-testid="support-attachment-error"
          className="text-xs text-danger"
        >
          {error}
        </p>
      )}
    </div>
  );
}
