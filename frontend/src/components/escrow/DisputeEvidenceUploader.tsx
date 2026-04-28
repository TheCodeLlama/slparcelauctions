"use client";

import { useState } from "react";

type Props = {
  files: File[];
  onChange: (files: File[]) => void;
  maxImages?: number;
  maxBytes?: number;
};

const ACCEPTED = ["image/png", "image/jpeg", "image/webp"];

export function DisputeEvidenceUploader({
  files,
  onChange,
  maxImages = 5,
  maxBytes = 5 * 1024 * 1024,
}: Props) {
  const [error, setError] = useState<string | null>(null);

  const handleAdd = (incoming: FileList | null) => {
    if (!incoming) return;
    const next = [...files];
    for (const f of Array.from(incoming)) {
      if (!ACCEPTED.includes(f.type)) {
        setError(`${f.name}: invalid type (${f.type})`);
        return;
      }
      if (f.size > maxBytes) {
        setError(`${f.name}: exceeds 5MB`);
        return;
      }
      if (next.length >= maxImages) {
        setError(`Max ${maxImages} images`);
        return;
      }
      next.push(f);
    }
    setError(null);
    onChange(next);
  };

  const remove = (i: number) => onChange(files.filter((_, j) => j !== i));

  return (
    <div>
      <label className="text-[10px] uppercase opacity-55 block mb-1">
        Images{" "}
        <span className="opacity-65 normal-case">
          (optional, max {maxImages}, 5MB each)
        </span>
      </label>
      <input
        type="file"
        multiple
        accept={ACCEPTED.join(",")}
        onChange={(e) => handleAdd(e.target.files)}
        className="text-xs"
      />
      {error && <p className="text-[10px] text-error mt-1">{error}</p>}
      {files.length > 0 && (
        <ul className="mt-2 space-y-1">
          {files.map((f, i) => (
            <li
              key={i}
              className="flex justify-between text-[11px] bg-surface-container-low p-1 rounded"
            >
              <span>
                {f.name}{" "}
                <span className="opacity-50">({Math.round(f.size / 1024)}KB)</span>
              </span>
              <button
                type="button"
                onClick={() => remove(i)}
                className="text-error"
              >
                remove
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
