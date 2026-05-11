"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";
import { Button } from "@/components/ui/Button";
import { Card } from "@/components/ui/Card";
import { Modal } from "@/components/ui/Modal";
import {
  useDissolveGroup,
  useTransferLeadership,
} from "@/hooks/realty/useRealtyGroups";
import type {
  OldLeaderAction,
  RealtyGroupPublicDto,
} from "@/types/realty";

export interface SettingsTabProps {
  group: RealtyGroupPublicDto;
}

/**
 * Leader-only Settings tab on the manage page.
 *
 * Two irreversible actions:
 *
 *   1. Transfer leadership — select a current agent as the new leader,
 *      choose to stay (with all four permissions) or leave on transfer.
 *   2. Dissolve group — soft-delete via the backend's
 *      {@code DELETE /api/v1/realty-groups/{publicId}}; redirects to
 *      `/dashboard/groups` on success.
 *
 * Both actions are gated behind a confirm modal because the spec lists
 * them as leader-only and irreversible.
 */
export function SettingsTab({ group }: SettingsTabProps) {
  const router = useRouter();
  const transfer = useTransferLeadership();
  const dissolve = useDissolveGroup();

  const [transferOpen, setTransferOpen] = useState(false);
  const [newLeaderId, setNewLeaderId] = useState<string>("");
  const [oldLeaderAction, setOldLeaderAction] = useState<OldLeaderAction>(
    "STAY",
  );

  const [dissolveOpen, setDissolveOpen] = useState(false);
  const [dissolveText, setDissolveText] = useState("");

  function handleTransferSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (!newLeaderId) return;
    transfer.mutate(
      {
        publicId: group.publicId,
        body: { newLeaderPublicId: newLeaderId, oldLeaderAction },
      },
      {
        onSuccess: () => {
          setTransferOpen(false);
          // If the old leader chose LEAVE, the group disappears from
          // their manage surface — bounce them to the groups index.
          if (oldLeaderAction === "LEAVE") {
            router.push("/dashboard/groups");
          }
        },
      },
    );
  }

  function handleDissolveConfirm() {
    dissolve.mutate(group.publicId, {
      onSuccess: () => {
        setDissolveOpen(false);
        router.push("/dashboard/groups");
      },
    });
  }

  // Build a select list of current agents (leader excluded — they're
  // the one transferring).
  const transferCandidates = group.agents.filter((a) => a.role !== "LEADER");

  return (
    <div className="flex flex-col gap-4">
      <Card>
        <Card.Header>
          <h2 className="text-sm font-semibold tracking-tight">
            Transfer leadership
          </h2>
        </Card.Header>
        <Card.Body>
          <p className="text-sm text-fg-muted mb-3">
            Hand the group over to one of your current agents. You can stay on
            as an agent with full permissions, or leave on transfer.
          </p>
          <Button
            type="button"
            variant="secondary"
            onClick={() => setTransferOpen(true)}
            disabled={transferCandidates.length === 0}
            data-testid="settings-transfer-button"
          >
            Transfer leadership
          </Button>
          {transferCandidates.length === 0 && (
            <p className="text-xs text-fg-muted mt-2">
              Invite at least one agent before transferring leadership.
            </p>
          )}
        </Card.Body>
      </Card>

      <Card>
        <Card.Header>
          <h2 className="text-sm font-semibold tracking-tight text-danger">
            Dissolve group
          </h2>
        </Card.Header>
        <Card.Body>
          <p className="text-sm text-fg-muted mb-3">
            Dissolution is permanent. All members lose access immediately.
            Existing listings are unlinked from the group but remain live.
          </p>
          <Button
            type="button"
            variant="destructive"
            onClick={() => setDissolveOpen(true)}
            data-testid="settings-dissolve-button"
          >
            Dissolve group
          </Button>
        </Card.Body>
      </Card>

      <Modal
        open={transferOpen}
        title="Transfer leadership"
        onClose={() => setTransferOpen(false)}
      >
        <form
          onSubmit={handleTransferSubmit}
          className="flex flex-col gap-4"
          aria-label="Transfer leadership"
        >
          <div className="flex flex-col gap-1">
            <label
              htmlFor="transfer-new-leader"
              className="text-xs font-medium text-fg-muted"
            >
              New leader
            </label>
            <select
              id="transfer-new-leader"
              value={newLeaderId}
              onChange={(e) => setNewLeaderId(e.target.value)}
              className="w-full rounded-lg bg-bg-subtle px-4 py-2 text-sm text-fg ring-1 ring-transparent focus:outline-none focus:ring-brand"
              data-testid="settings-transfer-select"
            >
              <option value="">Pick an agent...</option>
              {transferCandidates.map((a) => (
                <option key={a.userPublicId} value={a.userPublicId}>
                  {a.displayName}
                </option>
              ))}
            </select>
          </div>
          <fieldset className="flex flex-col gap-2">
            <legend className="text-xs font-medium text-fg-muted mb-1">
              Your role after transfer
            </legend>
            <label className="flex items-center gap-2 text-sm text-fg cursor-pointer">
              <input
                type="radio"
                name="old-leader-action"
                value="STAY"
                checked={oldLeaderAction === "STAY"}
                onChange={() => setOldLeaderAction("STAY")}
                data-testid="settings-transfer-stay"
              />
              Stay as an agent (full permissions)
            </label>
            <label className="flex items-center gap-2 text-sm text-fg cursor-pointer">
              <input
                type="radio"
                name="old-leader-action"
                value="LEAVE"
                checked={oldLeaderAction === "LEAVE"}
                onChange={() => setOldLeaderAction("LEAVE")}
                data-testid="settings-transfer-leave"
              />
              Leave the group
            </label>
          </fieldset>
          <p className="text-xs text-fg-muted">
            This action is irreversible.
          </p>
          <div className="flex justify-end gap-2">
            <Button
              type="button"
              variant="secondary"
              onClick={() => setTransferOpen(false)}
              disabled={transfer.isPending}
            >
              Cancel
            </Button>
            <Button
              type="submit"
              variant="primary"
              disabled={!newLeaderId || transfer.isPending}
              loading={transfer.isPending}
              data-testid="settings-transfer-confirm"
            >
              Transfer
            </Button>
          </div>
        </form>
      </Modal>

      <Modal
        open={dissolveOpen}
        title="Dissolve this group?"
        onClose={() => setDissolveOpen(false)}
        footer={
          <>
            <Button
              variant="secondary"
              onClick={() => setDissolveOpen(false)}
              disabled={dissolve.isPending}
            >
              Cancel
            </Button>
            <Button
              variant="destructive"
              onClick={handleDissolveConfirm}
              disabled={dissolveText !== group.name || dissolve.isPending}
              loading={dissolve.isPending}
              data-testid="settings-dissolve-confirm"
            >
              Dissolve
            </Button>
          </>
        }
      >
        <div className="flex flex-col gap-3">
          <p>
            This will dissolve <strong>{group.name}</strong> permanently. All
            members lose access. Existing listings are unlinked from the
            group.
          </p>
          <label className="flex flex-col gap-1 text-xs text-fg-muted">
            Type the group name to confirm
            <input
              type="text"
              value={dissolveText}
              onChange={(e) => setDissolveText(e.target.value)}
              className="rounded-lg bg-bg-subtle px-3 py-2 text-sm text-fg ring-1 ring-transparent focus:outline-none focus:ring-danger"
              data-testid="settings-dissolve-confirm-input"
              placeholder={group.name}
            />
          </label>
        </div>
      </Modal>
    </div>
  );
}
