"use client";
import { useRef, useEffect } from "react";
import { useInlineEdit } from "@/hooks/useInlineEdit";
import { FormError } from "@/components/ui/FormError";

export interface EditableDescriptionProps {
  value: string;
  onSave: (next: string) => Promise<void>;
}

/**
 * Click-to-edit description block. Saves on blur (Enter inserts a newline,
 * matching the buyer-facing rendered behavior). Esc cancels. Errors render
 * inline; editor stays open.
 */
export function EditableDescription({ value, onSave }: EditableDescriptionProps) {
  const edit = useInlineEdit<string>({ initialValue: value, onSave });
  const ref = useRef<HTMLTextAreaElement | null>(null);

  useEffect(() => {
    if (edit.state === "editing") ref.current?.focus();
  }, [edit.state]);

  if (edit.state === "idle") {
    return (
      <button
        type="button"
        onClick={edit.startEdit}
        data-testid="editable-description"
        className="whitespace-pre-wrap text-sm text-fg text-left w-full hover:opacity-80 focus:outline-none focus-visible:ring-2 focus-visible:ring-brand rounded"
      >
        {value || "Click to add a description"}
      </button>
    );
  }

  return (
    <div className="flex flex-col gap-1 w-full">
      <textarea
        ref={ref}
        rows={6}
        value={edit.draft}
        onChange={(e) => edit.setDraft(e.target.value)}
        onBlur={() => {
          edit.commit().catch(() => {});
        }}
        onKeyDown={(e) => {
          if (e.key === "Escape") edit.cancel();
        }}
        disabled={edit.state === "saving"}
        maxLength={5000}
        data-testid="editable-description-input"
        className="w-full resize-y rounded-lg bg-bg-subtle px-4 py-3 text-fg ring-1 ring-border-subtle focus:outline-none focus-visible:ring-2 focus-visible:ring-brand"
      />
      <FormError message={edit.error ?? undefined} />
    </div>
  );
}
