"use client";
import { useState } from "react";
import { Dialog, DialogPanel, DialogTitle } from "@headlessui/react";
import { Button } from "@/components/ui/Button";
import { FormError } from "@/components/ui/FormError";
import {
  AuctionSettingsForm,
  type AuctionSettingsValue,
} from "@/components/listing/AuctionSettingsForm";
import type { DraftSettings } from "./draftEditorMutations";

export interface EditableSettingsModalProps {
  value: DraftSettings;
  onSave: (next: DraftSettings) => Promise<void>;
  /** When set, renders this trigger instead of the default stats grid. */
  renderTrigger?: (open: () => void) => React.ReactNode;
}

/**
 * Click-to-edit auction settings. Opens a modal containing the existing
 * {@link AuctionSettingsForm}, so the four coupled fields (starting / reserve
 * / buy-now / duration + snipe) save as a group — the cross-field validation
 * (`buyNow > reserve > startingBid`) is preserved.
 */
export function EditableSettingsModal({
  value,
  onSave,
  renderTrigger,
}: EditableSettingsModalProps) {
  const [open, setOpen] = useState(false);
  const [draft, setDraft] = useState<AuctionSettingsValue>({ ...value });
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  function start() {
    setDraft({ ...value });
    setError(null);
    setOpen(true);
  }

  async function commit() {
    setSaving(true);
    try {
      await onSave({
        startingBid: draft.startingBid,
        reservePrice: draft.reservePrice,
        buyNowPrice: draft.buyNowPrice,
        durationHours: draft.durationHours,
        snipeProtect: draft.snipeProtect,
        snipeWindowMin: draft.snipeWindowMin,
      });
      setOpen(false);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Save failed");
    } finally {
      setSaving(false);
    }
  }

  return (
    <>
      {renderTrigger ? (
        renderTrigger(start)
      ) : (
        <button
          type="button"
          onClick={start}
          data-testid="editable-settings-trigger"
          className="text-left hover:opacity-80 focus:outline-none focus-visible:ring-2 focus-visible:ring-brand rounded"
        >
          Edit auction settings
        </button>
      )}
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
          <DialogPanel className="w-full max-w-2xl max-h-[90vh] overflow-y-auto flex flex-col gap-4 rounded-lg bg-bg-subtle p-6">
            <DialogTitle className="text-base font-bold tracking-tight text-fg">
              Edit auction settings
            </DialogTitle>
            <AuctionSettingsForm value={draft} onChange={setDraft} />
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
                data-testid="editable-settings-save"
              >
                Save
              </Button>
            </div>
          </DialogPanel>
        </div>
      </Dialog>
    </>
  );
}
