"use client";
import { useState } from "react";
import { Dialog, DialogPanel, DialogTitle } from "@headlessui/react";
import { Button } from "@/components/ui/Button";
import { FormError } from "@/components/ui/FormError";
import { Tag as TagIcon, Plus } from "@/components/ui/icons";
import { TagSelector } from "@/components/listing/TagSelector";
import type { ParcelTagDto } from "@/types/parcelTag";

export interface EditableTagsProps {
  value: ParcelTagDto[];
  onSave: (codes: string[]) => Promise<void>;
}

/**
 * Click-to-edit tag chips. Idle render shows the existing tag chips plus
 * a "+ Add tags" trigger; clicking opens a modal with the existing
 * {@link TagSelector}. Done saves the new selection; X / Esc cancels.
 */
export function EditableTags({ value, onSave }: EditableTagsProps) {
  const [open, setOpen] = useState(false);
  const [draft, setDraft] = useState<string[]>(value.map((t) => t.code));
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  function start() {
    setDraft(value.map((t) => t.code));
    setError(null);
    setOpen(true);
  }

  async function commit() {
    setSaving(true);
    try {
      await onSave(draft);
      setOpen(false);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Save failed");
    } finally {
      setSaving(false);
    }
  }

  return (
    <>
      <div className="flex flex-wrap items-center gap-1">
        {value.map((t) => (
          <span
            key={t.code}
            className="inline-flex items-center gap-1 rounded-full bg-bg-hover px-2 py-0.5 text-[11px] font-medium text-fg-muted"
          >
            <TagIcon className="size-3" aria-hidden="true" />
            {t.label}
          </span>
        ))}
        <button
          type="button"
          onClick={start}
          data-testid="editable-tags-trigger"
          className="inline-flex items-center gap-1 rounded-full bg-brand-soft px-2 py-0.5 text-[11px] font-medium text-brand hover:opacity-80 focus:outline-none focus-visible:ring-2 focus-visible:ring-brand"
        >
          <Plus className="size-3" aria-hidden="true" />
          Edit tags
        </button>
      </div>
      <Dialog
        open={open}
        onClose={() => (saving ? null : setOpen(false))}
        className="relative z-50"
      >
        <div
          className="fixed inset-0 bg-inverse-surface/40 backdrop-blur-sm"
          aria-hidden="true"
        />
        <div className="fixed inset-0 flex items-center justify-center p-4">
          <DialogPanel className="w-full max-w-lg flex flex-col gap-4 rounded-lg bg-bg-subtle p-6">
            <DialogTitle className="text-base font-bold tracking-tight text-fg">
              Edit tags
            </DialogTitle>
            <TagSelector value={draft} onChange={setDraft} />
            <FormError message={error ?? undefined} />
            <div className="flex justify-end gap-2">
              <Button
                variant="secondary"
                onClick={() => setOpen(false)}
                disabled={saving}
              >
                Cancel
              </Button>
              <Button
                variant="primary"
                onClick={commit}
                disabled={saving}
                loading={saving}
                data-testid="editable-tags-done"
              >
                Done
              </Button>
            </div>
          </DialogPanel>
        </div>
      </Dialog>
    </>
  );
}
