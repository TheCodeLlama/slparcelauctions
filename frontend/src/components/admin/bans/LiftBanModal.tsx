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
          className="w-full max-w-md rounded-lg bg-bg-subtle border border-border-subtle shadow-md p-6 flex flex-col gap-4"
          onClick={(e) => e.stopPropagation()}
        >
          <div className="flex items-start justify-between gap-2">
            <div>
              <h2 className="text-sm font-semibold text-fg">Lift ban</h2>
              <div className="mt-1 flex items-center gap-2">
                <BanTypeBadge banType={ban.banType} />
                <span className="text-sm text-fg-muted">
                  {ban.avatarLinkedDisplayName ?? ban.slAvatarUuid ?? ban.ipAddress ?? `#${ban.id}`}
                </span>
              </div>
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
              <label className="text-xs font-medium text-fg" htmlFor="lift-reason">
                Reason for lifting <span className="text-danger">*</span>
              </label>
              <textarea
                id="lift-reason"
                rows={4}
                value={liftedReason}
                disabled={liftBan.isPending}
                onChange={(e) => setLiftedReason(e.target.value.slice(0, NOTES_MAX))}
                placeholder="Explain why this ban is being lifted…"
                data-testid="lift-reason-textarea"
                className="w-full resize-y rounded-lg bg-bg-muted px-4 py-3 text-fg placeholder:text-fg-muted ring-1 ring-border-subtle transition-all focus:outline-none focus:ring-2 focus:ring-brand disabled:opacity-50"
              />
              <div className="self-end text-[11px] font-medium text-fg-muted">
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
