"use client";

import { useEffect, useState } from "react";
import { Button } from "@/components/ui/Button";
import { Input } from "@/components/ui/Input";
import { Modal } from "@/components/ui/Modal";
import {
  useAdminDissolveGroup,
  useAdminUpdateGroup,
} from "@/hooks/realty/useRealtyGroups";
import type { RealtyGroupRowDto } from "@/types/realty";

export type AdminRealtyGroupAction = "edit" | "dissolve";

export interface AdminRealtyGroupActionModalProps {
  open: boolean;
  action: AdminRealtyGroupAction;
  row: RealtyGroupRowDto;
  onClose: () => void;
}

/**
 * Confirmation + form modal for admin row actions on `/admin/realty-groups`.
 *
 * Two modes:
 *
 *   - `edit` — inline form to rename the group (admin bypasses the 30-day
 *     rename cooldown). Description / website / fees are intentionally not
 *     surfaced here; for richer edits, navigate to the detail page where
 *     the full admin profile form lives.
 *   - `dissolve` — destructive confirm. Requires typing the group name to
 *     proceed, matching the leader-side {@code SettingsTab} pattern.
 *
 * Force-remove-member is intentionally **not** offered here — it requires a
 * member list (and possibly a replacement-leader picker), which is a much
 * better fit for the detail page than a row-action modal.
 */
export function AdminRealtyGroupActionModal({
  open,
  action,
  row,
  onClose,
}: AdminRealtyGroupActionModalProps) {
  const update = useAdminUpdateGroup();
  const dissolve = useAdminDissolveGroup();

  const [name, setName] = useState(row.name);
  const [dissolveText, setDissolveText] = useState("");

  useEffect(() => {
    if (!open) return;
    // eslint-disable-next-line react-hooks/set-state-in-effect -- intentional: reset form state when modal opens.
    setName(row.name);
    setDissolveText("");
  }, [open, row.name]);

  if (!open) return null;

  if (action === "edit") {
    const trimmed = name.trim();
    const canSubmit =
      trimmed.length > 0 && trimmed.length <= 64 && trimmed !== row.name && !update.isPending;

    function handleEditSubmit(e: React.FormEvent) {
      e.preventDefault();
      if (!canSubmit) return;
      update.mutate(
        { publicId: row.publicId, body: { name: trimmed } },
        { onSuccess: onClose },
      );
    }

    return (
      <Modal
        open={open}
        title="Force-edit group name"
        onClose={onClose}
        footer={
          <>
            <Button
              variant="secondary"
              type="button"
              onClick={onClose}
              disabled={update.isPending}
            >
              Cancel
            </Button>
            <Button
              variant="primary"
              type="submit"
              form="admin-realty-edit-form"
              disabled={!canSubmit}
              loading={update.isPending}
              data-testid="admin-realty-edit-submit"
            >
              Save
            </Button>
          </>
        }
      >
        <form
          id="admin-realty-edit-form"
          onSubmit={handleEditSubmit}
          className="flex flex-col gap-3"
          aria-label="Force-edit group name"
        >
          <p className="text-xs text-fg-muted">
            Admin edits bypass the 30-day rename cooldown and do not bump the
            leader&apos;s next-rename window.
          </p>
          <Input
            label="Name"
            value={name}
            onChange={(e) => setName(e.target.value.slice(0, 64))}
            maxLength={64}
            autoFocus
            data-testid="admin-realty-edit-name"
          />
          <p className="text-[11px] text-fg-muted">
            Current slug: <code>{row.slug}</code> &middot; The slug will be
            recomputed from the new name on save.
          </p>
        </form>
      </Modal>
    );
  }

  // action === "dissolve"
  const canDissolve = dissolveText === row.name && !dissolve.isPending;

  function handleDissolveConfirm() {
    dissolve.mutate(row.publicId, { onSuccess: onClose });
  }

  return (
    <Modal
      open={open}
      title="Force-dissolve this group?"
      onClose={onClose}
      footer={
        <>
          <Button
            variant="secondary"
            type="button"
            onClick={onClose}
            disabled={dissolve.isPending}
          >
            Cancel
          </Button>
          <Button
            variant="destructive"
            onClick={handleDissolveConfirm}
            disabled={!canDissolve}
            loading={dissolve.isPending}
            data-testid="admin-realty-dissolve-confirm"
          >
            Dissolve
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-3">
        <p>
          This will dissolve <strong>{row.name}</strong> permanently. All
          members lose access. Existing listings under this group are unlinked
          (the FK is set NULL).
        </p>
        <label className="flex flex-col gap-1 text-xs text-fg-muted">
          Type the group name to confirm
          <input
            type="text"
            value={dissolveText}
            onChange={(e) => setDissolveText(e.target.value)}
            className="rounded-lg bg-bg-subtle px-3 py-2 text-sm text-fg ring-1 ring-transparent focus:outline-none focus:ring-danger"
            data-testid="admin-realty-dissolve-input"
            placeholder={row.name}
          />
        </label>
      </div>
    </Modal>
  );
}
