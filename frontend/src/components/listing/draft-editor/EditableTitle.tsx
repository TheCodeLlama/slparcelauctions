"use client";
import { useRef, useEffect } from "react";
import { useInlineEdit } from "@/hooks/useInlineEdit";
import { FormError } from "@/components/ui/FormError";

export interface EditableTitleProps {
  value: string;
  onSave: (next: string) => Promise<void>;
}

/**
 * Click-to-edit headline. Idle render shows the title text as a button;
 * clicking swaps it for an inline input matching the headline typography.
 * Saves on Enter or blur, cancels on Esc. Errors render inline below the
 * input via {@link FormError} and the editor stays open.
 */
export function EditableTitle({ value, onSave }: EditableTitleProps) {
  const edit = useInlineEdit<string>({ initialValue: value, onSave });
  const inputRef = useRef<HTMLInputElement | null>(null);

  useEffect(() => {
    if (edit.state === "editing") inputRef.current?.focus();
  }, [edit.state]);

  if (edit.state === "idle") {
    return (
      <button
        type="button"
        onClick={edit.startEdit}
        data-testid="editable-title"
        className="text-2xl font-bold tracking-tight text-fg text-left hover:opacity-80 focus:outline-none focus-visible:ring-2 focus-visible:ring-brand rounded"
      >
        {value || "(unnamed parcel)"}
      </button>
    );
  }

  return (
    <div className="flex flex-col gap-1">
      <input
        ref={inputRef}
        type="text"
        value={edit.draft}
        onChange={(e) => edit.setDraft(e.target.value)}
        onBlur={() => {
          edit.commit().catch(() => {});
        }}
        onKeyDown={(e) => {
          if (e.key === "Enter") {
            e.preventDefault();
            edit.commit().catch(() => {});
          } else if (e.key === "Escape") {
            edit.cancel();
          }
        }}
        disabled={edit.state === "saving"}
        maxLength={120}
        data-testid="editable-title-input"
        className="text-2xl font-bold tracking-tight text-fg bg-bg-subtle ring-1 ring-border-subtle rounded-lg px-3 py-2 focus:outline-none focus-visible:ring-2 focus-visible:ring-brand"
      />
      <FormError message={edit.error ?? undefined} />
    </div>
  );
}
