"use client";
import { useState, useEffect } from "react";
import { useReinstateAuction } from "@/hooks/admin/useReinstateAuction";
import { Button } from "@/components/ui/Button";
import type { AdminUserListingRow } from "@/lib/admin/types";

const NOTES_MAX = 1000;

type Props = {
  auction: AdminUserListingRow;
  userId: number;
  onClose: () => void;
};

export function ReinstateListingModal({ auction, userId, onClose }: Props) {
  const [notes, setNotes] = useState("");
  const reinstate = useReinstateAuction(userId);

  useEffect(() => {
    function onKeyDown(e: KeyboardEvent) {
      if (e.key === "Escape") onClose();
    }
    document.addEventListener("keydown", onKeyDown);
    return () => document.removeEventListener("keydown", onKeyDown);
  }, [onClose]);

  const canSubmit = notes.trim().length > 0 && !reinstate.isPending;

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (!canSubmit) return;
    reinstate.mutate(
      { auctionId: auction.auctionId, notes: notes.trim() },
      { onSuccess: onClose }
    );
  }

  return (
    <>
      <div
        className="fixed inset-0 z-40 bg-inverse-surface/20"
        onClick={onClose}
        aria-hidden="true"
      />
      <div
        role="dialog"
        aria-modal="true"
        aria-label="Reinstate listing"
        data-testid="reinstate-listing-modal"
        className="fixed inset-0 z-50 flex items-center justify-center p-4"
      >
        <div
          className="w-full max-w-md rounded-lg bg-bg-subtle border border-border-subtle shadow-md p-6 flex flex-col gap-4"
          onClick={(e) => e.stopPropagation()}
        >
          <div className="flex items-start justify-between gap-2">
            <div>
              <h2 className="text-sm font-semibold text-fg">Reinstate listing</h2>
              <p className="mt-1 text-sm text-fg-muted line-clamp-2">
                {auction.title}
                {auction.regionName ? ` · ${auction.regionName}` : ""}
              </p>
            </div>
            <button
              type="button"
              onClick={onClose}
              aria-label="Close"
              className="p-1.5 rounded-lg text-fg-muted hover:bg-bg-muted"
            >
              ✕
            </button>
          </div>

          <form onSubmit={handleSubmit} className="flex flex-col gap-4">
            <div className="flex flex-col gap-1">
              <label htmlFor="reinstate-notes" className="text-xs font-medium text-fg">
                Notes <span className="text-danger">*</span>
              </label>
              <textarea
                id="reinstate-notes"
                rows={4}
                value={notes}
                disabled={reinstate.isPending}
                onChange={(e) => setNotes(e.target.value.slice(0, NOTES_MAX))}
                placeholder="Explain why this listing is being reinstated…"
                data-testid="reinstate-notes-textarea"
                className="w-full resize-y rounded-lg bg-bg-muted px-4 py-3 text-fg placeholder:text-fg-muted ring-1 ring-border-subtle transition-all focus:outline-none focus:ring-2 focus:ring-brand disabled:opacity-50"
              />
              <div className="self-end text-[11px] font-medium text-fg-muted">
                {notes.length} / {NOTES_MAX}
              </div>
            </div>

            <div className="flex justify-end gap-2">
              <Button variant="secondary" type="button" onClick={onClose} disabled={reinstate.isPending}>
                Cancel
              </Button>
              <Button
                variant="primary"
                type="submit"
                disabled={!canSubmit}
                loading={reinstate.isPending}
                data-testid="reinstate-listing-submit"
              >
                Reinstate
              </Button>
            </div>
          </form>
        </div>
      </div>
    </>
  );
}
