"use client";
import { useState, useEffect } from "react";
import { useLiftBan } from "@/hooks/admin/useLiftBan";
import { Button } from "@/components/ui/Button";
import { BanTypeBadge } from "./BanTypeBadge";
import type { AdminBanRow } from "@/lib/admin/types";

const NOTES_MAX = 1000;

type Props = {
  ban: AdminBanRow;
  onClose: () => void;
};

export function LiftBanModal({ ban, onClose }: Props) {
  const [liftedReason, setLiftedReason] = useState("");
  const liftBan = useLiftBan();

  useEffect(() => {
    function onKeyDown(e: KeyboardEvent) {
      if (e.key === "Escape") onClose();
    }
    document.addEventListener("keydown", onKeyDown);
    return () => document.removeEventListener("keydown", onKeyDown);
  }, [onClose]);

  const canSubmit = liftedReason.trim().length > 0 && !liftBan.isPending;

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (!canSubmit) return;
    liftBan.mutate(
      { id: ban.id, liftedReason },
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
        aria-label="Lift ban"
        data-testid="lift-ban-modal"
        className="fixed inset-0 z-50 flex items-center justify-center p-4"
      >
        <div
          className="w-full max-w-md rounded-default bg-surface-container-low border border-outline-variant shadow-elevated p-6 flex flex-col gap-4"
          onClick={(e) => e.stopPropagation()}
        >
          <div className="flex items-start justify-between gap-2">
            <div>
              <h2 className="text-title-md font-semibold text-on-surface">Lift ban</h2>
              <div className="mt-1 flex items-center gap-2">
                <BanTypeBadge banType={ban.banType} />
                <span className="text-body-sm text-on-surface-variant">
                  {ban.avatarLinkedDisplayName ?? ban.slAvatarUuid ?? ban.ipAddress ?? `#${ban.id}`}
                </span>
              </div>
            </div>
            <button
              type="button"
              onClick={onClose}
              aria-label="Close"
              className="p-1.5 rounded-default text-on-surface-variant hover:bg-surface-container"
            >
              ✕
            </button>
          </div>

          <form onSubmit={handleSubmit} className="flex flex-col gap-4">
            <div className="flex flex-col gap-1">
              <label className="text-label-md font-medium text-on-surface" htmlFor="lift-reason">
                Reason for lifting <span className="text-error">*</span>
              </label>
              <textarea
                id="lift-reason"
                rows={4}
                value={liftedReason}
                disabled={liftBan.isPending}
                onChange={(e) => setLiftedReason(e.target.value.slice(0, NOTES_MAX))}
                placeholder="Explain why this ban is being lifted…"
                data-testid="lift-reason-textarea"
                className="w-full resize-y rounded-default bg-surface-container px-4 py-3 text-on-surface placeholder:text-on-surface-variant ring-1 ring-outline-variant transition-all focus:outline-none focus:ring-primary disabled:opacity-50"
              />
              <div className="self-end text-label-sm text-on-surface-variant">
                {liftedReason.length} / {NOTES_MAX}
              </div>
            </div>

            <div className="flex justify-end gap-2">
              <Button variant="secondary" type="button" onClick={onClose} disabled={liftBan.isPending}>
                Cancel
              </Button>
              <Button
                variant="destructive"
                type="submit"
                disabled={!canSubmit}
                loading={liftBan.isPending}
                data-testid="lift-ban-submit"
              >
                Lift ban
              </Button>
            </div>
          </form>
        </div>
      </div>
    </>
  );
}
