"use client";

import { useEffect, useRef } from "react";
import {
  DndContext,
  type DragEndEvent,
  PointerSensor,
  KeyboardSensor,
  useSensor,
  useSensors,
} from "@dnd-kit/core";
import {
  SortableContext,
  useSortable,
  rectSortingStrategy,
  sortableKeyboardCoordinates,
} from "@dnd-kit/sortable";
import { CSS } from "@dnd-kit/utilities";
import { DropZone } from "@/components/ui/DropZone";
import { AlertTriangle, GripVertical, Trash2 } from "@/components/ui/icons";
import { cn } from "@/lib/cn";
import { resizeImage } from "@/lib/image/resizeImage";
import {
  revokeStagedPhoto,
  stagePhoto,
  validateFile,
  type StagedPhoto,
} from "@/lib/listing/photoStaging";

/**
 * Pure helper: given a staged-photos array and a drag-end event's active
 * + over ids, returns the new ordered array, or null if the drag is a
 * no-op. Exported so unit tests can drive the reorder logic without
 * simulating dnd-kit interactions in jsdom.
 */
export function applyStagedDragEnd(
  staged: StagedPhoto[],
  activeId: string,
  overId: string | null,
): StagedPhoto[] | null {
  if (!overId || activeId === overId) return null;
  const oldIndex = staged.findIndex((p) => p.id === activeId);
  const newIndex = staged.findIndex((p) => p.id === overId);
  if (oldIndex < 0 || newIndex < 0) return null;
  const next = [...staged];
  const [moved] = next.splice(oldIndex, 1);
  next.splice(newIndex, 0, moved);
  return next;
}

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

  async function addFiles(files: File[]) {
    if (disabled) return;
    const remaining = Math.max(0, maxPhotos - staged.length);
    const take = files.slice(0, remaining);
    if (take.length === 0) return;
    const additions: StagedPhoto[] = await Promise.all(
      take.map(async (raw) => {
        const err = validateFile(raw);
        if (err) {
          const p = stagePhoto(raw);
          p.error = err;
          return p;
        }
        // Resize before staging so the on-disk preview, the upload payload,
        // and the eventual S3 object are all the same already-shrunk bytes.
        // Runs in a Web Worker so the main thread keeps repainting.
        const resized = await resizeImage(raw, { maxDim: 2048 });
        return stagePhoto(resized);
      }),
    );
    onStagedChange([...staged, ...additions]);
  }

  function remove(id: string) {
    const p = staged.find((s) => s.id === id);
    if (p) revokeStagedPhoto(p);
    onStagedChange(staged.filter((s) => s.id !== id));
  }

  const atCap = staged.length >= maxPhotos;

  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 5 } }),
    useSensor(KeyboardSensor, { coordinateGetter: sortableKeyboardCoordinates }),
  );

  function handleDragEnd(event: DragEndEvent) {
    const next = applyStagedDragEnd(
      staged,
      String(event.active.id),
      event.over?.id ? String(event.over.id) : null,
    );
    if (!next) return;
    onStagedChange(next);
  }

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
        <DndContext sensors={sensors} onDragEnd={handleDragEnd}>
          <SortableContext
            items={staged.map((p) => p.id)}
            strategy={rectSortingStrategy}
          >
            <ul className={cn("grid grid-cols-2 gap-2 sm:grid-cols-4")}>
              {staged.map((p) => (
                <SortableStagedPhoto
                  key={p.id}
                  photo={p}
                  disabled={disabled}
                  onRemove={() => remove(p.id)}
                />
              ))}
            </ul>
          </SortableContext>
        </DndContext>
      )}
    </div>
  );
}

function SortableStagedPhoto({
  photo,
  disabled,
  onRemove,
}: {
  photo: StagedPhoto;
  disabled: boolean;
  onRemove: () => void;
}) {
  const {
    attributes,
    listeners,
    setNodeRef,
    transform,
    transition,
    isDragging,
  } = useSortable({ id: photo.id });

  const style = {
    transform: CSS.Transform.toString(transform),
    transition,
    opacity: isDragging ? 0.4 : 1,
    zIndex: isDragging ? 10 : undefined,
  };

  return (
    <li
      ref={setNodeRef}
      style={style}
      data-testid="staged-photo"
      data-photo-error={photo.error ? "true" : undefined}
      {...attributes}
      {...listeners}
      className="relative overflow-hidden rounded-lg border border-border-subtle cursor-grab active:cursor-grabbing touch-none focus:outline-none focus-visible:ring-2 focus-visible:ring-brand"
      aria-label="Staged photo, drag to reorder"
    >
      {/* eslint-disable-next-line @next/next/no-img-element */}
      <img
        src={photo.objectUrl}
        alt=""
        className="pointer-events-none h-24 w-full object-cover"
        draggable={false}
      />
      {photo.error && (
        <div className="pointer-events-none absolute inset-0 flex flex-col items-center justify-center gap-1 bg-danger-bg/90 p-2 text-center text-xs text-danger">
          <AlertTriangle className="size-4" aria-hidden="true" />
          <span>{photo.error}</span>
        </div>
      )}
      <span
        aria-hidden="true"
        className="pointer-events-none absolute left-1 top-1 rounded-full bg-surface-raised/90 p-1 text-fg-muted"
      >
        <GripVertical className="size-3.5" />
      </span>
      <button
        type="button"
        onClick={onRemove}
        onPointerDown={(e) => e.stopPropagation()}
        disabled={disabled || isDragging}
        aria-label="Remove photo"
        className="absolute right-1 top-1 rounded-full bg-surface-raised/90 p-1 text-danger hover:bg-surface-raised disabled:opacity-50"
      >
        <Trash2 className="size-3.5" aria-hidden="true" />
      </button>
      {photo.uploadedPhotoId == null ? (
        <span className="pointer-events-none absolute bottom-1 left-1 rounded-full bg-bg-hover/90 px-2 py-0.5 text-[11px] font-medium text-fg-muted">
          staged
        </span>
      ) : null}
    </li>
  );
}
