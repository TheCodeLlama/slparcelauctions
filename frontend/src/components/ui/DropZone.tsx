"use client";

import { useRef, useState, type DragEvent } from "react";
import { UploadCloud } from "./icons";
import { cn } from "@/lib/cn";

export type DropZoneProps = {
  onFiles: (files: File[]) => void;
  accept?: string;
  multiple?: boolean;
  disabled?: boolean;
  label?: string;
  className?: string;
};

/**
 * Generic drag-and-drop file surface. Forwards selected or dropped files to
 * the `onFiles` callback. Used by the listing photo uploader in sub-spec 2,
 * but kept generic so it can be reused (e.g., future avatar / kyc uploads).
 *
 * Accessibility: the wrapper is focusable via role="button" and clicking it
 * triggers the hidden native <input type="file">. Disabled state is mirrored
 * onto both the wrapper (aria-disabled) and the input (disabled) so the
 * browser blocks selection while still letting screen readers announce state.
 */
export function DropZone({
  onFiles,
  accept,
  multiple = true,
  disabled,
  label,
  className,
}: DropZoneProps) {
  const inputRef = useRef<HTMLInputElement>(null);
  const [over, setOver] = useState(false);

  function handleDrop(e: DragEvent<HTMLDivElement>) {
    e.preventDefault();
    setOver(false);
    if (disabled) return;
    const files = Array.from(e.dataTransfer.files ?? []);
    if (files.length > 0) onFiles(files);
  }

  return (
    <div
      data-testid="drop-zone"
      className={cn(
        "flex cursor-pointer flex-col items-center justify-center gap-2 rounded-default border-2 border-dashed p-6 text-center transition-colors",
        over && !disabled
          ? "border-primary bg-primary-container/20"
          : "border-outline-variant",
        disabled && "cursor-not-allowed opacity-60",
        className,
      )}
      onClick={() => !disabled && inputRef.current?.click()}
      onDragOver={(e) => {
        e.preventDefault();
        if (!disabled) setOver(true);
      }}
      onDragLeave={() => setOver(false)}
      onDrop={handleDrop}
      role="button"
      aria-disabled={disabled}
    >
      <UploadCloud
        className="size-7 text-on-surface-variant"
        aria-hidden="true"
      />
      <span className="text-body-sm text-on-surface-variant">
        {label ?? "Drag files here, or click to select"}
      </span>
      <input
        data-testid="drop-zone-input"
        ref={inputRef}
        type="file"
        accept={accept}
        multiple={multiple}
        disabled={disabled}
        className="sr-only"
        onChange={(e) => {
          const files = Array.from(e.target.files ?? []);
          if (files.length > 0) onFiles(files);
          e.target.value = "";
        }}
      />
    </div>
  );
}
