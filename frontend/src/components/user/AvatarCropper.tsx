"use client";

import { useCallback, useState } from "react";
import Cropper, { type Area } from "react-easy-crop";
import { Button } from "@/components/ui/Button";
import { getCroppedImg } from "@/lib/avatar/cropImage";

interface Props {
  imageSrc: string;
  onSave: (blob: Blob) => void | Promise<void>;
  onCancel?: () => void;
  saveLabel?: string;
  cancelLabel?: string;
}

/**
 * Shared circular-preview crop UI used on the avatar onboarding gate AND
 * on the settings ProfilePictureUploader. Pan via drag, zoom via slider
 * or scroll/pinch. Save runs the canvas extraction at 512x512 and hands
 * the resulting PNG Blob to the parent's {@code onSave}.
 *
 * <p>The component owns its own crop / zoom / saving state. The parent
 * stays out of cropper internals — it only sees the final Blob (or a
 * Cancel signal). This is what lets the same component back two
 * unrelated UX flows.
 */
export function AvatarCropper({
  imageSrc,
  onSave,
  onCancel,
  saveLabel = "Save",
  cancelLabel = "Cancel",
}: Props) {
  const [crop, setCrop] = useState({ x: 0, y: 0 });
  const [zoom, setZoom] = useState(1);
  const [croppedAreaPixels, setCroppedAreaPixels] = useState<Area | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  const onCropComplete = useCallback((_area: Area, areaPixels: Area) => {
    setCroppedAreaPixels(areaPixels);
  }, []);

  const handleSave = async () => {
    if (!croppedAreaPixels || saving) return;
    setSaving(true);
    setError(null);
    try {
      const blob = await getCroppedImg(imageSrc, croppedAreaPixels);
      await onSave(blob);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to save");
    } finally {
      setSaving(false);
    }
  };

  if (error) {
    return (
      <div
        className="rounded-md bg-bg-subtle p-6 text-center"
        data-testid="avatar-cropper-error"
      >
        <p className="text-sm text-fg-muted mb-4">
          We couldn&apos;t use that image. Please try a different one.
        </p>
        {onCancel && (
          <Button variant="secondary" onClick={onCancel}>
            Pick a different image
          </Button>
        )}
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-4" data-testid="avatar-cropper">
      <div className="relative h-80 w-full overflow-hidden rounded-md bg-bg-subtle">
        <Cropper
          image={imageSrc}
          crop={crop}
          zoom={zoom}
          aspect={1}
          cropShape="round"
          showGrid={false}
          onCropChange={setCrop}
          onZoomChange={setZoom}
          onCropComplete={onCropComplete}
        />
      </div>
      <label className="flex items-center gap-3">
        <span className="text-xs font-medium text-fg-muted w-12">Zoom</span>
        <input
          type="range"
          min={1}
          max={3}
          step={0.01}
          value={zoom}
          onChange={(e) => setZoom(Number(e.target.value))}
          aria-label="Zoom"
          className="flex-1"
        />
      </label>
      <div className="flex flex-wrap gap-2">
        <Button onClick={handleSave} disabled={saving || !croppedAreaPixels}>
          {saving ? "Saving..." : saveLabel}
        </Button>
        {onCancel && (
          <Button variant="secondary" onClick={onCancel} disabled={saving}>
            {cancelLabel}
          </Button>
        )}
      </div>
    </div>
  );
}
