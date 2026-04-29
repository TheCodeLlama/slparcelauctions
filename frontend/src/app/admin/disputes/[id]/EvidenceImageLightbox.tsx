"use client";

import { useState } from "react";
import type { DisputeEvidenceImageDto } from "@/lib/admin/disputes";

type Props = { images: DisputeEvidenceImageDto[] };

export function EvidenceImageLightbox({ images }: Props) {
  const [idx, setIdx] = useState<number | null>(null);
  if (images.length === 0) return null;

  return (
    <>
      <div className="grid grid-cols-3 gap-1">
        {images.map((img, i) => (
          <button
            key={img.s3Key}
            type="button"
            className="aspect-square overflow-hidden rounded bg-surface-container-low"
            onClick={() => setIdx(i)}
          >
            <img src={img.presignedUrl} alt="" className="w-full h-full object-cover" />
          </button>
        ))}
      </div>
      {idx !== null && (
        <div
          className="fixed inset-0 bg-black/70 z-50 flex items-center justify-center cursor-pointer"
          onClick={() => setIdx(null)}
        >
          <img src={images[idx].presignedUrl} alt="" className="max-w-[90vw] max-h-[90vh]" />
        </div>
      )}
    </>
  );
}
