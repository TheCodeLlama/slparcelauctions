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
import { GripVertical, ImagePlus, Moon, Trash2 } from "@/components/ui/icons";
import { Dialog, DialogPanel, DialogTitle } from "@headlessui/react";
import { Button } from "@/components/ui/Button";
import { apiUrl } from "@/lib/api/url";
import { PhotoVariantsModal } from "@/components/auction/photo-manager/PhotoVariantsModal";
import type { AuctionPhotoDto } from "@/types/auction";

const MAX_PHOTOS = 10;

/**
 * True for the sort-0 default-cover photo (copied from the seller's or
 * group's persisted default cover). Only this row supports a dark theme
 * variant; seller uploads and SL parcel snapshots stay single-slot.
 */
function isDefaultCover(photo: AuctionPhotoDto): boolean {
  return (
    photo.source === "USER_DEFAULT_COVER" ||
    photo.source === "GROUP_DEFAULT_COVER"
  );
}

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
  /**
   * Auction public id — required so the default-cover tile can open the
   * {@link PhotoVariantsModal} against the dark-variant endpoints.
   */
  auctionPublicId: string;
  photos: AuctionPhotoDto[];
  snapshotUrl?: string | null;
  regionName?: string;
  onReorder: (orderedPublicIds: string[]) => Promise<void>;
  onDelete: (photoPublicId: string) => Promise<void>;
  onAdd: (file: File) => Promise<void>;
}

export function EditablePhotoGallery({
  auctionPublicId,
  photos,
  snapshotUrl,
  regionName,
  onReorder,
  onDelete,
  onAdd,
}: EditablePhotoGalleryProps) {
  const sorted = [...photos].sort((a, b) => a.sortOrder - b.sortOrder);
  const [confirmDelete, setConfirmDelete] = useState<string | null>(null);
  const [variantsPhotoId, setVariantsPhotoId] = useState<string | null>(null);
  const fileInputRef = useRef<HTMLInputElement | null>(null);

  // Resolve the live photo for the open modal from the current photos
  // array so a refetch (or reorder) keeps the modal's slots in sync.
  const variantsPhoto =
    variantsPhotoId != null
      ? sorted.find((p) => p.publicId === variantsPhotoId) ?? null
      : null;

  // 5px activation distance lets click events on the delete X / drag handle
  // dispatch normally; only sustained drags engage the sensor.
  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 5 } }),
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
      ? apiUrl(sorted[0].lightUrl) ?? sorted[0].lightUrl
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
                onEditVariants={
                  isDefaultCover(photo)
                    ? () => setVariantsPhotoId(photo.publicId)
                    : undefined
                }
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

      {variantsPhoto && (
        <PhotoVariantsModal
          open
          onClose={() => setVariantsPhotoId(null)}
          auctionPublicId={auctionPublicId}
          photo={variantsPhoto}
        />
      )}
    </div>
  );
}

function SortablePhotoTile({
  photo,
  onDeleteRequest,
  onEditVariants,
}: {
  photo: AuctionPhotoDto;
  onDeleteRequest: () => void;
  /**
   * When set, this tile is the default-cover photo: render the "Light +
   * Dark" / "Add dark" badge and wire it to open the variants modal.
   * Undefined for ordinary single-slot photos.
   */
  onEditVariants?: () => void;
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
    opacity: isDragging ? 0.4 : 1,
    zIndex: isDragging ? 10 : undefined,
  };

  return (
    <li
      ref={setNodeRef}
      style={style}
      data-testid={`editable-photo-tile-${photo.publicId}`}
      {...attributes}
      {...listeners}
      className="relative h-24 overflow-hidden rounded-lg border border-border-subtle bg-bg-subtle cursor-grab active:cursor-grabbing touch-none focus:outline-none focus-visible:ring-2 focus-visible:ring-brand"
      aria-label={`Photo ${photo.publicId}, drag to reorder`}
    >
      <img
        src={apiUrl(photo.lightUrl) ?? photo.lightUrl}
        alt=""
        className="pointer-events-none h-full w-full object-cover"
        draggable={false}
      />
      <span
        aria-hidden="true"
        className="pointer-events-none absolute left-1 top-1 rounded-full bg-surface-raised/90 p-1 text-fg-muted"
      >
        <GripVertical className="size-3.5" />
      </span>
      <button
        type="button"
        onClick={onDeleteRequest}
        onPointerDown={(e) => e.stopPropagation()}
        disabled={isDragging}
        aria-label={`Remove photo ${photo.publicId}`}
        className="absolute right-1 top-1 rounded-full bg-surface-raised/90 p-1 text-danger hover:bg-surface-raised disabled:opacity-50 focus:outline-none focus-visible:ring-2 focus-visible:ring-brand"
      >
        <Trash2 className="size-3.5" aria-hidden="true" />
      </button>
      {onEditVariants && (
        <button
          type="button"
          onClick={onEditVariants}
          onPointerDown={(e) => e.stopPropagation()}
          disabled={isDragging}
          aria-label="Edit light and dark variants"
          title="Edit light and dark variants"
          data-testid={`editable-photo-variants-${photo.publicId}`}
          className="absolute bottom-1 left-1 flex items-center gap-1 rounded-full bg-surface-raised/90 px-1.5 py-0.5 text-[10px] font-medium text-fg-muted hover:bg-surface-raised hover:text-fg disabled:opacity-50 focus:outline-none focus-visible:ring-2 focus-visible:ring-brand"
        >
          <Moon className="size-3" aria-hidden="true" />
          <span>{photo.darkUrl ? "Light + Dark" : "Add dark"}</span>
        </button>
      )}
    </li>
  );
}
