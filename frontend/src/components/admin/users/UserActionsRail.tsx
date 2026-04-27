"use client";
import { useState } from "react";
import Link from "next/link";
import { usePromoteUser } from "@/hooks/admin/usePromoteUser";
import { useDemoteUser } from "@/hooks/admin/useDemoteUser";
import { useResetFrivolousCounter } from "@/hooks/admin/useResetFrivolousCounter";
import { ConfirmActionModal } from "./ConfirmActionModal";
import { RecentIpsModal } from "./RecentIpsModal";
import { CreateBanModal } from "@/components/admin/bans/CreateBanModal";
import { LiftBanModal } from "@/components/admin/bans/LiftBanModal";
import { Button } from "@/components/ui/Button";
import type { AdminUserDetail, AdminBanRow } from "@/lib/admin/types";

type Action = "promote" | "demote" | "resetFrivolous" | null;

type Props = {
  user: AdminUserDetail;
  onRefresh: () => void;
};

export function UserActionsRail({ user, onRefresh }: Props) {
  const [pendingAction, setPendingAction] = useState<Action>(null);
  const [showIps, setShowIps] = useState(false);
  const [showCreateBan, setShowCreateBan] = useState(false);
  const [showLiftBan, setShowLiftBan] = useState(false);

  const promote = usePromoteUser(user.id);
  const demote = useDemoteUser(user.id);
  const resetFrivolous = useResetFrivolousCounter(user.id);

  const activeBanAsRow: AdminBanRow | null = user.activeBan
    ? {
        id: user.activeBan.id,
        banType: user.activeBan.banType,
        ipAddress: null,
        slAvatarUuid: user.slAvatarUuid,
        avatarLinkedUserId: user.id,
        avatarLinkedDisplayName: user.displayName,
        firstSeenIp: null,
        reasonCategory: "OTHER",
        reasonText: user.activeBan.reasonText,
        bannedByUserId: 0,
        bannedByDisplayName: null,
        expiresAt: user.activeBan.expiresAt,
        createdAt: new Date().toISOString(),
        liftedAt: null,
        liftedByUserId: null,
        liftedByDisplayName: null,
        liftedReason: null,
      }
    : null;

  function handleConfirm(notes: string) {
    if (pendingAction === "promote") {
      promote.mutate(notes, { onSuccess: () => { setPendingAction(null); onRefresh(); } });
    } else if (pendingAction === "demote") {
      demote.mutate(notes, { onSuccess: () => { setPendingAction(null); onRefresh(); } });
    } else if (pendingAction === "resetFrivolous") {
      resetFrivolous.mutate(notes, { onSuccess: () => { setPendingAction(null); onRefresh(); } });
    }
  }

  const isMutating = promote.isPending || demote.isPending || resetFrivolous.isPending;

  const modalProps = {
    promote: {
      title: "Promote to admin",
      description: `Grant ${user.displayName ?? user.email} admin privileges?`,
      confirmLabel: "Promote",
      confirmVariant: "primary" as const,
    },
    demote: {
      title: "Demote from admin",
      description: `Remove admin privileges from ${user.displayName ?? user.email}?`,
      confirmLabel: "Demote",
      confirmVariant: "destructive" as const,
    },
    resetFrivolous: {
      title: "Reset frivolous counter",
      description: `Reset the frivolous cancellation counter for ${user.displayName ?? user.email}?`,
      confirmLabel: "Reset",
      confirmVariant: "primary" as const,
    },
  };

  const currentModal = pendingAction ? modalProps[pendingAction] : null;

  return (
    <aside
      className="w-[280px] shrink-0 flex flex-col gap-3"
      data-testid="user-actions-rail"
    >
      {/* Active ban callout */}
      {user.activeBan && (
        <div className="rounded-default bg-error-container border border-error/20 p-4 flex flex-col gap-2">
          <div className="text-label-sm font-semibold text-on-error-container">Active ban</div>
          <div className="text-body-sm text-on-error-container/80 line-clamp-3">
            {user.activeBan.reasonText}
          </div>
          {user.activeBan.expiresAt && (
            <div className="text-[11px] text-on-error-container/70">
              Expires {new Date(user.activeBan.expiresAt).toLocaleDateString()}
            </div>
          )}
          {activeBanAsRow && (
            <Button
              variant="destructive"
              size="sm"
              onClick={() => setShowLiftBan(true)}
              data-testid="lift-ban-btn"
            >
              Lift ban
            </Button>
          )}
        </div>
      )}

      {/* Role actions */}
      <div className="rounded-default bg-surface-container border border-outline-variant p-4 flex flex-col gap-2">
        <div className="text-label-sm font-medium text-on-surface-variant mb-1">Role</div>

        {user.role === "USER" && (
          <Button
            variant="secondary"
            size="sm"
            fullWidth
            onClick={() => setPendingAction("promote")}
            data-testid="promote-btn"
          >
            Promote to admin
          </Button>
        )}

        {user.role === "ADMIN" && (
          <Button
            variant="destructive"
            size="sm"
            fullWidth
            onClick={() => setPendingAction("demote")}
            data-testid="demote-btn"
          >
            Demote from admin
          </Button>
        )}
      </div>

      {/* Frivolous counter */}
      {user.cancelledWithBids > 0 && (
        <div className="rounded-default bg-surface-container border border-outline-variant p-4 flex flex-col gap-2">
          <div className="text-label-sm font-medium text-on-surface-variant mb-1">
            Frivolous cancellations: {user.cancelledWithBids}
          </div>
          <Button
            variant="secondary"
            size="sm"
            fullWidth
            onClick={() => setPendingAction("resetFrivolous")}
            data-testid="reset-frivolous-btn"
          >
            Reset counter
          </Button>
        </div>
      )}

      {/* Bans */}
      <div className="rounded-default bg-surface-container border border-outline-variant p-4 flex flex-col gap-2">
        <div className="text-label-sm font-medium text-on-surface-variant mb-1">Bans</div>
        <Button
          variant="secondary"
          size="sm"
          fullWidth
          onClick={() => setShowCreateBan(true)}
          data-testid="add-ban-btn"
        >
          + Add ban
        </Button>
      </div>

      {/* Quick links */}
      <div className="rounded-default bg-surface-container border border-outline-variant p-4 flex flex-col gap-2">
        <div className="text-label-sm font-medium text-on-surface-variant mb-1">Quick links</div>
        <Link
          href={`/users/${user.id}`}
          className="text-body-sm text-primary hover:underline underline-offset-2"
          target="_blank"
          data-testid="public-profile-link"
        >
          Public profile
        </Link>
        {user.slAvatarUuid && (
          <button
            type="button"
            onClick={() => navigator.clipboard.writeText(user.slAvatarUuid!)}
            className="text-left text-body-sm text-on-surface-variant hover:text-on-surface transition-colors"
            data-testid="copy-uuid-btn"
          >
            Copy SL UUID
          </button>
        )}
        <button
          type="button"
          onClick={() => setShowIps(true)}
          className="text-left text-body-sm text-on-surface-variant hover:text-on-surface transition-colors"
          data-testid="recent-ips-btn"
        >
          Recent IPs
        </button>
      </div>

      {/* Modals */}
      {currentModal && (
        <ConfirmActionModal
          open={pendingAction !== null}
          title={currentModal.title}
          description={currentModal.description}
          confirmLabel={currentModal.confirmLabel}
          confirmVariant={currentModal.confirmVariant}
          isPending={isMutating}
          onConfirm={handleConfirm}
          onClose={() => setPendingAction(null)}
        />
      )}

      {showIps && (
        <RecentIpsModal userId={user.id} onClose={() => setShowIps(false)} />
      )}

      <CreateBanModal
        open={showCreateBan}
        onClose={() => setShowCreateBan(false)}
        initialSlAvatarUuid={user.slAvatarUuid ?? undefined}
      />

      {showLiftBan && activeBanAsRow && (
        <LiftBanModal
          ban={activeBanAsRow}
          onClose={() => { setShowLiftBan(false); onRefresh(); }}
        />
      )}
    </aside>
  );
}
