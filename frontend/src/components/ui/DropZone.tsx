"use client";

import { useRef, useState, type DragEvent, type KeyboardEvent } from "react";
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
 * Accessibility: the wrapper is focusable via role="button" + tabIndex and
 * responds to Enter/Space to open the native file picker (WCAG 2.1 §2.1.1).
 * Clicking it also triggers the hidden native <input type="file">. Disabled
 * state is mirrored onto both the wrapper (aria-disabled + tabIndex={-1}) and
 * the input (disabled) so the browser blocks selection while still letting
 * screen readers announce state.
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

  function handleKeyDown(e: KeyboardEvent<HTMLDivElement>) {
    if (disabled) return;
    if (e.key === "Enter" || e.key === " ") {
      e.preventDefault();
      inputRef.current?.click();
    }
  }

  return (
    <div
      data-testid="drop-zone"
      className={cn(
        "flex cursor-pointer flex-col items-center justify-center gap-2 rounded-lg border-2 border-dashed p-6 text-center transition-colors",
        "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand",
        over && !disabled
          ? "border-brand bg-brand-soft/20"
          : "border-border-subtle",
        disabled && "cursor-not-allowed opacity-60",
        className,
      )}
      onClick={() => !disabled && inputRef.current?.click()}
      onKeyDown={handleKeyDown}
      onDragOver={(e) => {
        e.preventDefault();
        if (!disabled) setOver(true);
      }}
      onDragLeave={(e) => {
        if (!e.currentTarget.contains(e.relatedTarget as Node | null)) setOver(false);
      }}
      onDrop={handleDrop}
      role="button"
      tabIndex={disabled ? -1 : 0}
      aria-disabled={disabled}
    >
      <UploadCloud
        className="size-7 text-fg-muted"
        aria-hidden="true"
      />
      <span className="text-xs text-fg-muted">
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
