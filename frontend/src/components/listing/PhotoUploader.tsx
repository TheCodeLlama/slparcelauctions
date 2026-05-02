"use client";

import { useEffect, useRef } from "react";
import { DropZone } from "@/components/ui/DropZone";
import { AlertTriangle, Trash2 } from "@/components/ui/icons";
import { cn } from "@/lib/cn";
import {
  revokeStagedPhoto,
  stagePhoto,
  validateFile,
  type StagedPhoto,
} from "@/lib/listing/photoStaging";

export interface PhotoUploaderProps {
  staged: StagedPhoto[];
  onStagedChange: (next: StagedPhoto[]) => void;
  disabled?: boolean;
  /** Max listing photos per spec §4.1 — 10. */
  maxPhotos?: number;
}

/**
 * Drag-and-drop photo staging widget. Uses the generic DropZone primitive
 * (Task 6) for the drop surface. Staging is in-memory only — the parent
 * owns the StagedPhoto[] state and is responsible for uploading to the
 * backend once an auction id exists.
 *
 * Revokes object URLs on unmount to avoid leaking Blob URLs. We track the
 * last-seen staged list in a ref so the cleanup can revoke exactly what's
 * in state at unmount time without re-subscribing the effect on every
 * staged change (which would cause spurious revokes mid-edit).
 */
export function PhotoUploader({
  staged,
  onStagedChange,
  disabled = false,
  maxPhotos = 10,
}: PhotoUploaderProps) {
  // Track the latest staged list in an effect-synced ref so the unmount
  // cleanup below can revoke exactly what's in state without re-running
  // on every staged change (which would spuriously revoke URLs while the
  // seller is still editing). Updating the ref in an effect — not during
  // render — complies with the react-hooks/refs lint rule.
  const stagedRef = useRef<StagedPhoto[]>(staged);
  useEffect(() => {
    stagedRef.current = staged;
  }, [staged]);

  useEffect(() => {
    return () => {
      for (const p of stagedRef.current) revokeStagedPhoto(p);
    };
  }, []);

  function addFiles(files: File[]) {
    if (disabled) return;
    const remaining = Math.max(0, maxPhotos - staged.length);
    const take = files.slice(0, remaining);
    const additions: StagedPhoto[] = take.map((f) => {
      const err = validateFile(f);
      const p = stagePhoto(f);
      p.error = err;
      return p;
    });
    if (additions.length === 0) return;
    onStagedChange([...staged, ...additions]);
  }

  function remove(id: string) {
    const p = staged.find((s) => s.id === id);
    if (p) revokeStagedPhoto(p);
    onStagedChange(staged.filter((s) => s.id !== id));
  }

  const atCap = staged.length >= maxPhotos;

  return (
    <div className="flex flex-col gap-3">
      <DropZone
        onFiles={addFiles}
        accept="image/jpeg,image/png,image/webp"
        multiple
        disabled={disabled || atCap}
        label={
          atCap
            ? `Maximum ${maxPhotos} photos reached.`
            : "Drag and drop photos, or click to pick."
        }
      />
      {staged.length > 0 && (
        <ul
          className={cn(
            "grid grid-cols-2 gap-2 sm:grid-cols-4",
          )}
        >
          {staged.map((p) => (
            <li
              key={p.id}
              data-testid="staged-photo"
              data-photo-error={p.error ? "true" : undefined}
              className="relative overflow-hidden rounded-lg border border-border-subtle"
            >
              {/* eslint-disable-next-line @next/next/no-img-element */}
              <img
                src={p.objectUrl}
                alt=""
                className="h-24 w-full object-cover"
              />
              {p.error && (
                <div className="absolute inset-0 flex flex-col items-center justify-center gap-1 bg-danger-bg/90 p-2 text-center text-xs text-danger-flat">
                  <AlertTriangle className="size-4" aria-hidden="true" />
                  <span>{p.error}</span>
                </div>
              )}
              <button
                type="button"
                onClick={() => remove(p.id)}
                disabled={disabled}
                aria-label="Remove photo"
                className="absolute right-1 top-1 rounded-full bg-surface-raised/90 p-1 text-danger-flat hover:bg-surface-raised disabled:opacity-50"
              >
                <Trash2 className="size-3.5" aria-hidden="true" />
              </button>
              {p.uploadedPhotoId == null ? (
                <span className="absolute bottom-1 left-1 rounded-full bg-bg-hover/90 px-2 py-0.5 text-[11px] font-medium text-fg-muted">
                  staged
                </span>
              ) : null}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
