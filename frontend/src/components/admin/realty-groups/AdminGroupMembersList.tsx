"use client";

import { useMemo, useState } from "react";
import { Avatar } from "@/components/ui/Avatar";
import { Button } from "@/components/ui/Button";
import { Card } from "@/components/ui/Card";
import { Modal } from "@/components/ui/Modal";
import { StatusBadge } from "@/components/ui/StatusBadge";
import { useAdminRemoveMember } from "@/hooks/realty/useRealtyGroups";
import { permissionLabel } from "@/lib/realty/permissions";
import type { AgentCardDto, RealtyGroupPublicDto } from "@/types/realty";

export interface AdminGroupMembersListProps {
  group: RealtyGroupPublicDto;
}

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
  const all = [leaderRow, ...group.agents];
  return all.sort((a, b) =>
    a.displayName.localeCompare(b.displayName, "en", { sensitivity: "base" }),
  );
}

/**
 * Members list on the admin detail page. Admin can force-remove any member,
 * including the leader (with a replacement-leader picker).
 *
 * Removing the leader without a replacement is rejected at the backend with
 * a 400; this UI surfaces an explicit replacement picker that defaults to
 * the first available agent. If the group has no other members, the UI
 * shows a helper note telling the admin to force-dissolve instead.
 */
export function AdminGroupMembersList({ group }: AdminGroupMembersListProps) {
  const removeMember = useAdminRemoveMember();

  const [removeTarget, setRemoveTarget] = useState<AgentCardDto | null>(null);
  const [replacementLeader, setReplacementLeader] = useState<string>("");

  const rows = useMemo(() => buildRows(group), [group]);

  const replacementCandidates = useMemo(
    () =>
      removeTarget && removeTarget.role === "LEADER"
        ? group.agents.filter((a) => a.role !== "LEADER")
        : [],
    [removeTarget, group.agents],
  );

  function handleConfirmRemove() {
    if (!removeTarget) return;
    const args = {
      publicId: group.publicId,
      memberPublicId: removeTarget.memberPublicId,
      ...(removeTarget.role === "LEADER" && replacementLeader
        ? { newLeaderPublicId: replacementLeader }
        : {}),
    };
    removeMember.mutate(args, {
      onSuccess: () => {
        setRemoveTarget(null);
        setReplacementLeader("");
      },
    });
  }

  function openRemove(member: AgentCardDto) {
    setRemoveTarget(member);
    if (member.role === "LEADER") {
      const firstAgent = group.agents.find((a) => a.role !== "LEADER");
      // Replacement-leader IDs flow into the backend's
      // `members.findByPublicId(newLeaderPublicId)` lookup, which keys on the
      // member-row UUID, NOT the user UUID. Sending a userPublicId there
      // would always 400 with TRANSFER_TARGET_NOT_MEMBER.
      setReplacementLeader(firstAgent?.memberPublicId ?? "");
    } else {
      setReplacementLeader("");
    }
  }

  const isLeaderRemoval = removeTarget?.role === "LEADER";
  const cannotRemoveLeader =
    isLeaderRemoval && replacementCandidates.length === 0;
  const canSubmitRemove =
    !!removeTarget &&
    !removeMember.isPending &&
    (!isLeaderRemoval || !!replacementLeader);

  return (
    <Card>
      <Card.Header>
        <h2 className="text-sm font-semibold tracking-tight">Members</h2>
      </Card.Header>
      <Card.Body>
        <ul
          className="flex flex-col gap-2"
          data-testid="admin-group-members-list"
        >
          {rows.map((m) => {
            const isLeaderRow = m.role === "LEADER";
            return (
              <li
                key={m.memberPublicId}
                className="flex items-center gap-3 rounded-lg border border-border bg-surface-raised px-3 py-2.5"
                data-testid={`admin-member-row-${m.userPublicId}`}
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
                    <ul className="flex flex-wrap gap-1">
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
                </div>
                <Button
                  type="button"
                  size="sm"
                  variant="destructive"
                  onClick={() => openRemove(m)}
                  data-testid={`admin-member-remove-${m.userPublicId}`}
                >
                  Force-remove
                </Button>
              </li>
            );
          })}
        </ul>
      </Card.Body>

      <Modal
        open={!!removeTarget}
        title={
          isLeaderRemoval
            ? "Force-remove leader"
            : `Remove ${removeTarget?.displayName ?? "member"}?`
        }
        onClose={() => {
          setRemoveTarget(null);
          setReplacementLeader("");
        }}
        footer={
          <>
            <Button
              variant="secondary"
              onClick={() => {
                setRemoveTarget(null);
                setReplacementLeader("");
              }}
              disabled={removeMember.isPending}
            >
              Cancel
            </Button>
            <Button
              variant="destructive"
              onClick={handleConfirmRemove}
              loading={removeMember.isPending}
              disabled={!canSubmitRemove || cannotRemoveLeader}
              data-testid="admin-member-remove-confirm"
            >
              Force-remove
            </Button>
          </>
        }
      >
        {isLeaderRemoval ? (
          cannotRemoveLeader ? (
            <p
              className="text-sm text-danger"
              data-testid="admin-member-remove-no-replacement"
            >
              This group has no other members. Force-dissolve the group
              instead.
            </p>
          ) : (
            <div className="flex flex-col gap-3">
              <p>
                Removing the leader requires promoting an existing agent.
              </p>
              <label className="flex flex-col gap-1 text-xs text-fg-muted">
                Replacement leader
                <select
                  value={replacementLeader}
                  onChange={(e) => setReplacementLeader(e.target.value)}
                  className="rounded-lg bg-bg-subtle px-3 py-2 text-sm text-fg ring-1 ring-transparent focus:outline-none focus:ring-brand"
                  data-testid="admin-member-replacement-select"
                >
                  {replacementCandidates.map((a) => (
                    <option key={a.memberPublicId} value={a.memberPublicId}>
                      {a.displayName}
                    </option>
                  ))}
                </select>
              </label>
            </div>
          )
        ) : (
          <p>
            This will remove the member immediately. Listings under this group
            are not affected.
          </p>
        )}
      </Modal>
    </Card>
  );
}
