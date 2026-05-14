"use client";

import { useMemo, useState } from "react";
import { Button } from "@/components/ui/Button";
import { Card } from "@/components/ui/Card";
import { Modal } from "@/components/ui/Modal";
import { useLeaveGroup, useRemoveMember } from "@/hooks/realty/useRealtyGroups";
import { permissionLabel } from "@/lib/realty/permissions";
import type {
  AgentCardDto,
  RealtyGroupPermission,
  RealtyGroupPublicDto,
} from "@/types/realty";
import { Avatar } from "@/components/ui/Avatar";
import { StatusBadge } from "@/components/ui/StatusBadge";
import { BulkMemberCommissionEditDrawer } from "./BulkMemberCommissionEditDrawer";
import { CommissionRateForm } from "./CommissionRateForm";
import { PermissionsForm } from "./PermissionsForm";

export interface MembersTabProps {
  group: RealtyGroupPublicDto;
  /** Set of permissions the caller has on this group. */
  callerPermissions: Set<RealtyGroupPermission>;
  isLeader: boolean;
  /** Caller's user publicId, for the "Leave group" affordance on own row. */
  callerUserPublicId: string | null;
}

/**
 * Combine leader + agents into one sortable, alpha-sorted roster.
 *
 * <p>The backend's {@code group.agents} array carries the leader as its own
 * row with role LEADER (so a single SQL projection backs both the leader
 * and the agents list). We strip that row before concatenating with the
 * synthesised leader card so the leader doesn't render twice.
 */
function buildRows(group: RealtyGroupPublicDto): AgentCardDto[] {
  const leaderRow: AgentCardDto = {
    memberPublicId: `leader-${group.leader.userPublicId}`,
    userPublicId: group.leader.userPublicId,
    displayName: group.leader.displayName,
    avatarUrl: group.leader.avatarUrl,
    role: "LEADER",
    permissions: null,
    joinedAt: null,
    agentCommissionRate: null,
  };
  const agentsOnly = group.agents.filter(
    (a) => a.userPublicId !== group.leader.userPublicId,
  );
  const all = [leaderRow, ...agentsOnly];
  return all.sort((a, b) =>
    a.displayName.localeCompare(b.displayName, "en", { sensitivity: "base" }),
  );
}

/**
 * Members tab on the manage page. Shows leader + agents, with row-level
 * actions gated by caller permissions:
 *
 * - Remove (REMOVE_AGENTS or leader; never targets the leader)
 * - Edit permissions (leader only)
 * - Leave group (caller's own row, only if caller is an agent)
 */
export function MembersTab({
  group,
  callerPermissions,
  isLeader,
  callerUserPublicId,
}: MembersTabProps) {
  const removeMember = useRemoveMember();
  const leaveGroup = useLeaveGroup();
  const [removeTarget, setRemoveTarget] = useState<AgentCardDto | null>(null);
  const [permissionsTarget, setPermissionsTarget] =
    useState<AgentCardDto | null>(null);
  const [rateTarget, setRateTarget] = useState<AgentCardDto | null>(null);
  const [leaveConfirm, setLeaveConfirm] = useState(false);
  const [bulkRatesOpen, setBulkRatesOpen] = useState(false);

  const canRemove = isLeader || callerPermissions.has("REMOVE_AGENTS");
  // Bulk commission edit is leader-only (MANAGE_MEMBERS holders also
  // qualify per spec §15.1). The button only renders when the group has
  // at least one agent — there's nothing to edit in an empty roster.
  const canBulkEditRates =
    (isLeader || callerPermissions.has("MANAGE_MEMBERS")) &&
    group.agents.length > 0;

  const rows = useMemo(() => buildRows(group), [group]);

  function handleConfirmRemove() {
    if (!removeTarget) return;
    removeMember.mutate(
      {
        publicId: group.publicId,
        memberPublicId: removeTarget.memberPublicId,
      },
      { onSuccess: () => setRemoveTarget(null) },
    );
  }

  function handleConfirmLeave() {
    leaveGroup.mutate(group.publicId, {
      onSuccess: () => setLeaveConfirm(false),
    });
  }

  return (
    <Card>
      <Card.Header>
        <div className="flex items-center justify-between gap-3">
          <h2 className="text-sm font-semibold tracking-tight">Members</h2>
          {canBulkEditRates && (
            <Button
              type="button"
              size="sm"
              variant="secondary"
              onClick={() => setBulkRatesOpen(true)}
              data-testid="members-bulk-edit-rates"
            >
              Bulk edit commission rates
            </Button>
          )}
        </div>
      </Card.Header>
      <Card.Body>
        <ul className="flex flex-col gap-2" data-testid="members-list">
          {rows.map((m) => {
            const isLeaderRow = m.role === "LEADER";
            const isOwnRow = m.userPublicId === callerUserPublicId;
            // Leader's own row should never show "Leave" (LEADER_CANNOT_LEAVE)
            // and never show "Remove" (CANNOT_REMOVE_LEADER). "Permissions"
            // and "Commission rate" are leader-only edits on non-leader rows.
            const showRemove = !isLeaderRow && canRemove;
            const showPermissions = !isLeaderRow && isLeader;
            const showCommission = !isLeaderRow && isLeader;
            const showLeave = isOwnRow && !isLeaderRow;
            return (
              <li
                key={m.memberPublicId}
                className="flex items-center gap-3 rounded-lg border border-border bg-surface-raised px-3 py-2.5"
                data-testid={`member-row-${m.userPublicId}`}
              >
                <Avatar
                  src={m.avatarUrl ?? undefined}
                  alt={m.displayName}
                  name={m.displayName}
                  size="sm"
                />
                <div className="flex flex-col gap-0.5 min-w-0 flex-1">
                  <div className="flex items-center gap-2 flex-wrap">
                    <span className="text-sm font-medium text-fg truncate">
                      {m.displayName}
                    </span>
                    <StatusBadge tone={isLeaderRow ? "success" : "default"}>
                      {isLeaderRow ? "Leader" : "Agent"}
                    </StatusBadge>
                  </div>
                  {m.permissions && m.permissions.length > 0 && (
                    <ul
                      className="flex flex-wrap gap-1"
                      data-testid={`member-permissions-${m.userPublicId}`}
                    >
                      {m.permissions.map((p) => (
                        <li
                          key={p}
                          className="inline-flex items-center rounded bg-info-bg px-1.5 py-0.5 text-[11px] font-medium text-info"
                        >
                          {permissionLabel(p)}
                        </li>
                      ))}
                    </ul>
                  )}
                  {!isLeaderRow && (
                    <span
                      className="text-[11px] text-fg-muted"
                      data-testid={`member-commission-rate-${m.userPublicId}`}
                    >
                      Commission:{" "}
                      {m.agentCommissionRate != null
                        ? `${(m.agentCommissionRate * 100).toFixed(2)}%`
                        : "Not set"}
                    </span>
                  )}
                </div>
                <div className="flex items-center gap-2 shrink-0">
                  {showPermissions && (
                    <Button
                      type="button"
                      size="sm"
                      variant="secondary"
                      onClick={() => setPermissionsTarget(m)}
                      data-testid={`member-permissions-edit-${m.userPublicId}`}
                    >
                      Permissions
                    </Button>
                  )}
                  {showCommission && (
                    <Button
                      type="button"
                      size="sm"
                      variant="secondary"
                      onClick={() => setRateTarget(m)}
                      data-testid={`member-commission-rate-edit-${m.userPublicId}`}
                    >
                      Commission rate
                    </Button>
                  )}
                  {showRemove && (
                    <Button
                      type="button"
                      size="sm"
                      variant="destructive"
                      onClick={() => setRemoveTarget(m)}
                      data-testid={`member-remove-${m.userPublicId}`}
                    >
                      Remove
                    </Button>
                  )}
                  {showLeave && (
                    <Button
                      type="button"
                      size="sm"
                      variant="secondary"
                      onClick={() => setLeaveConfirm(true)}
                      data-testid="member-leave-self"
                    >
                      Leave group
                    </Button>
                  )}
                </div>
              </li>
            );
          })}
        </ul>
      </Card.Body>

      <Modal
        open={!!removeTarget}
        title={`Remove ${removeTarget?.displayName ?? "member"}?`}
        onClose={() => setRemoveTarget(null)}
        footer={
          <>
            <Button
              variant="secondary"
              onClick={() => setRemoveTarget(null)}
              disabled={removeMember.isPending}
            >
              Cancel
            </Button>
            <Button
              variant="destructive"
              onClick={handleConfirmRemove}
              loading={removeMember.isPending}
              disabled={removeMember.isPending}
              data-testid="member-remove-confirm"
            >
              Remove member
            </Button>
          </>
        }
      >
        <p>
          They will lose access to the group and any pending invitations.
          Listings under this group are unaffected.
        </p>
      </Modal>

      <Modal
        open={!!permissionsTarget}
        title={`Permissions for ${permissionsTarget?.displayName ?? "member"}`}
        onClose={() => setPermissionsTarget(null)}
      >
        {permissionsTarget && (
          <PermissionsForm
            key={permissionsTarget.memberPublicId}
            groupPublicId={group.publicId}
            member={permissionsTarget}
            onComplete={() => setPermissionsTarget(null)}
          />
        )}
      </Modal>

      <Modal
        open={!!rateTarget}
        title={`Commission rate for ${rateTarget?.displayName ?? "member"}`}
        onClose={() => setRateTarget(null)}
      >
        {rateTarget && (
          <CommissionRateForm
            key={rateTarget.memberPublicId}
            groupPublicId={group.publicId}
            member={rateTarget}
            onComplete={() => setRateTarget(null)}
          />
        )}
      </Modal>

      <BulkMemberCommissionEditDrawer
        open={bulkRatesOpen}
        group={group}
        onClose={() => setBulkRatesOpen(false)}
      />

      <Modal
        open={leaveConfirm}
        title="Leave this group?"
        onClose={() => setLeaveConfirm(false)}
        footer={
          <>
            <Button
              variant="secondary"
              onClick={() => setLeaveConfirm(false)}
              disabled={leaveGroup.isPending}
            >
              Cancel
            </Button>
            <Button
              variant="destructive"
              onClick={handleConfirmLeave}
              loading={leaveGroup.isPending}
              disabled={leaveGroup.isPending}
              data-testid="member-leave-confirm"
            >
              Leave group
            </Button>
          </>
        }
      >
        <p>
          You will lose your agent permissions in this group. You can be
          re-invited later.
        </p>
      </Modal>
    </Card>
  );
}
