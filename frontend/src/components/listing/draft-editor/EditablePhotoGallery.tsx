/* eslint-disable @next/next/no-img-element */
"use client";
import { useRef, useState } from "react";
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
import { GripVertical, ImagePlus, Trash2 } from "@/components/ui/icons";
import { Dialog, DialogPanel, DialogTitle } from "@headlessui/react";
import { Button } from "@/components/ui/Button";
import { apiUrl } from "@/lib/api/url";
import type { AuctionPhotoDto } from "@/types/auction";

const MAX_PHOTOS = 10;

/**
 * Pure helper: given the current photos array and a drag-end event's
 * active+over ids, returns the new ordered array of photo publicIds, or
 * null if the drag is a no-op. Exported so the unit tests can drive the
 * reorder logic without simulating dnd-kit interactions in jsdom.
 */
export function applyDragEnd(
  photos: AuctionPhotoDto[],
  activeId: string,
  overId: string | null,
): string[] | null {
  if (!overId || activeId === overId) return null;
  const oldIndex = photos.findIndex((p) => p.publicId === activeId);
  const newIndex = photos.findIndex((p) => p.publicId === overId);
  if (oldIndex < 0 || newIndex < 0) return null;
  const next = [...photos];
  const [moved] = next.splice(oldIndex, 1);
  next.splice(newIndex, 0, moved);
  return next.map((p) => p.publicId);
}

export interface EditablePhotoGalleryProps {
  photos: AuctionPhotoDto[];
  snapshotUrl?: string | null;
  regionName?: string;
  onReorder: (orderedPublicIds: string[]) => Promise<void>;
  onDelete: (photoPublicId: string) => Promise<void>;
  onAdd: (file: File) => Promise<void>;
}

export function EditablePhotoGallery({
  photos,
  snapshotUrl,
  regionName,
  onReorder,
  onDelete,
  onAdd,
}: EditablePhotoGalleryProps) {
  const sorted = [...photos].sort((a, b) => a.sortOrder - b.sortOrder);
  const [confirmDelete, setConfirmDelete] = useState<string | null>(null);
  const fileInputRef = useRef<HTMLInputElement | null>(null);

  const sensors = useSensors(
    useSensor(PointerSensor),
    useSensor(KeyboardSensor, { coordinateGetter: sortableKeyboardCoordinates }),
  );

  function handleDragEnd(event: DragEndEvent) {
    const next = applyDragEnd(
      sorted,
      String(event.active.id),
      event.over?.id ? String(event.over.id) : null,
    );
    if (!next) return;
    onReorder(next).catch(() => {});
  }

  const heroSrc =
    sorted.length > 0
      ? apiUrl(sorted[0].url) ?? sorted[0].url
      : snapshotUrl
        ? apiUrl(snapshotUrl) ?? snapshotUrl
        : null;

  return (
    <div
      className="flex flex-col gap-3"
      data-testid="auction-hero"
      data-variant="editable"
    >
      {heroSrc ? (
        <div className="rounded-lg overflow-hidden h-[320px] md:h-[420px] bg-bg-subtle">
          <img
            src={heroSrc}
            alt={regionName ? `${regionName} cover` : ""}
            className="h-full w-full object-cover"
            data-testid="editable-photo-gallery-hero"
          />
        </div>
      ) : (
        <div
          className="rounded-lg h-[200px] bg-gradient-to-br from-brand-soft to-bg-hover flex items-center justify-center text-sm text-brand"
          data-testid="editable-photo-gallery-empty"
        >
          {regionName ?? "No photos yet"}
        </div>
      )}

      <DndContext sensors={sensors} onDragEnd={handleDragEnd}>
        <SortableContext
          items={sorted.map((p) => p.publicId)}
          strategy={rectSortingStrategy}
        >
          <ul
            className="grid grid-cols-3 gap-2 sm:grid-cols-5"
            data-testid="editable-photo-gallery-strip"
          >
            {sorted.map((photo) => (
              <SortablePhotoTile
                key={photo.publicId}
                photo={photo}
                onDeleteRequest={() => setConfirmDelete(photo.publicId)}
              />
            ))}
            {sorted.length < MAX_PHOTOS && (
              <li>
                <button
                  type="button"
                  onClick={() => fileInputRef.current?.click()}
                  data-testid="editable-photo-gallery-add"
                  className="flex h-24 w-full items-center justify-center rounded-lg border-2 border-dashed border-border-subtle text-fg-muted hover:bg-bg-hover focus:outline-none focus-visible:ring-2 focus-visible:ring-brand"
                  aria-label="Add photo"
                >
                  <ImagePlus className="size-5" aria-hidden="true" />
                </button>
                <input
                  ref={fileInputRef}
                  type="file"
                  accept="image/jpeg,image/png,image/webp"
                  className="hidden"
                  data-testid="editable-photo-gallery-file-input"
                  onChange={(e) => {
                    const f = e.target.files?.[0];
                    if (f) {
                      onAdd(f).catch(() => {});
                    }
                    if (e.target) e.target.value = "";
                  }}
                />
              </li>
            )}
          </ul>
        </SortableContext>
      </DndContext>

      <Dialog
        open={confirmDelete != null}
        onClose={() => setConfirmDelete(null)}
        className="relative z-50"
      >
        <div
          className="fixed inset-0 bg-inverse-surface/40 backdrop-blur-sm"
          aria-hidden="true"
        />
        <div className="fixed inset-0 flex items-center justify-center p-4">
          <DialogPanel className="w-full max-w-sm flex flex-col gap-4 rounded-lg bg-bg-subtle p-6">
            <DialogTitle className="text-base font-bold tracking-tight text-fg">
              Remove this photo?
            </DialogTitle>
            <p className="text-sm text-fg-muted">
              The photo will be deleted from this listing immediately.
            </p>
            <div className="flex justify-end gap-2">
              <Button
                variant="secondary"
                onClick={() => setConfirmDelete(null)}
              >
                Cancel
              </Button>
              <Button
                variant="destructive"
                onClick={async () => {
                  if (!confirmDelete) return;
                  try {
                    await onDelete(confirmDelete);
                  } finally {
                    setConfirmDelete(null);
                  }
                }}
                data-testid="editable-photo-gallery-confirm-delete"
              >
                Remove
              </Button>
            </div>
          </DialogPanel>
        </div>
      </Dialog>
    </div>
  );
}

function SortablePhotoTile({
  photo,
  onDeleteRequest,
}: {
  photo: AuctionPhotoDto;
  onDeleteRequest: () => void;
}) {
  const {
    attributes,
    listeners,
    setNodeRef,
    transform,
    transition,
    isDragging,
  } = useSortable({ id: photo.publicId });

  const style = {
    transform: CSS.Transform.toString(transform),
    transition,
    opacity: isDragging ? 0.5 : 1,
  };

  return (
    <li
      ref={setNodeRef}
      style={style}
      data-testid={`editable-photo-tile-${photo.publicId}`}
      className="relative h-24 overflow-hidden rounded-lg border border-border-subtle bg-bg-subtle"
    >
      <img
        src={apiUrl(photo.url) ?? photo.url}
        alt=""
        className="h-full w-full object-cover"
      />
      <button
        type="button"
        {...attributes}
        {...listeners}
        aria-label={`Drag photo ${photo.publicId}`}
        className="absolute left-1 top-1 rounded-full bg-surface-raised/90 p-1 text-fg-muted cursor-grab active:cursor-grabbing focus:outline-none focus-visible:ring-2 focus-visible:ring-brand"
      >
        <GripVertical className="size-3.5" aria-hidden="true" />
      </button>
      <button
        type="button"
        onClick={onDeleteRequest}
        disabled={isDragging}
        aria-label={`Remove photo ${photo.publicId}`}
        className="absolute right-1 top-1 rounded-full bg-surface-raised/90 p-1 text-danger hover:bg-surface-raised disabled:opacity-50 focus:outline-none focus-visible:ring-2 focus-visible:ring-brand"
      >
        <Trash2 className="size-3.5" aria-hidden="true" />
      </button>
    </li>
  );
}
