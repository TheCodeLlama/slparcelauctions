"use client";
import { useState } from "react";
import Link from "next/link";
import { usePromoteUser } from "@/hooks/admin/usePromoteUser";
import { useDemoteUser } from "@/hooks/admin/useDemoteUser";
import { useResetFrivolousCounter } from "@/hooks/admin/useResetFrivolousCounter";
import {
  useAdminWallet,
  useFreezeWallet,
  useUnfreezeWallet,
  useResetDormancy,
  useClearTerms,
} from "@/hooks/admin/useAdminWallet";
import { ConfirmActionModal } from "./ConfirmActionModal";
import { DeleteUserModal } from "./DeleteUserModal";
import { RecentIpsModal } from "./RecentIpsModal";
import { AdjustBalanceModal } from "./wallet/AdjustBalanceModal";
import { ForgivePenaltyModal } from "./wallet/ForgivePenaltyModal";
import { CreateBanModal } from "@/components/admin/bans/CreateBanModal";
import { LiftBanModal } from "@/components/admin/bans/LiftBanModal";
import { Button } from "@/components/ui/Button";
import type { AdminUserDetail, AdminBanRow } from "@/lib/admin/types";

type Action =
  | "promote" | "demote" | "resetFrivolous"
  | "freeze" | "unfreeze" | "resetDormancy" | "clearTerms"
  | null;

type Props = {
  user: AdminUserDetail;
  onRefresh: () => void;
};

export function UserActionsRail({ user, onRefresh }: Props) {
  const [pendingAction, setPendingAction] = useState<Action>(null);
  const [showIps, setShowIps] = useState(false);
  const [showCreateBan, setShowCreateBan] = useState(false);
  const [showLiftBan, setShowLiftBan] = useState(false);
  const [showDeleteUser, setShowDeleteUser] = useState(false);
  const [showAdjustBalance, setShowAdjustBalance] = useState(false);
  const [showForgivePenalty, setShowForgivePenalty] = useState(false);

  const promote = usePromoteUser(user.publicId);
  const demote = useDemoteUser(user.publicId);
  const resetFrivolous = useResetFrivolousCounter(user.publicId);
  const freeze = useFreezeWallet(user.publicId);
  const unfreeze = useUnfreezeWallet(user.publicId);
  const resetDormancy = useResetDormancy(user.publicId);
  const clearTerms = useClearTerms(user.publicId);
  const { data: wallet } = useAdminWallet(user.publicId);

  const activeBanAsRow: AdminBanRow | null = user.activeBan
    ? {
        id: user.activeBan.id,
        banType: user.activeBan.banType,
        ipAddress: null,
        slAvatarUuid: user.slAvatarUuid,
        avatarLinkedUserId: null,
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
    } else if (pendingAction === "freeze") {
      freeze.mutate({ notes }, { onSuccess: () => { setPendingAction(null); onRefresh(); } });
    } else if (pendingAction === "unfreeze") {
      unfreeze.mutate({ notes }, { onSuccess: () => { setPendingAction(null); onRefresh(); } });
    } else if (pendingAction === "resetDormancy") {
      resetDormancy.mutate({ notes }, { onSuccess: () => { setPendingAction(null); onRefresh(); } });
    } else if (pendingAction === "clearTerms") {
      clearTerms.mutate({ notes }, { onSuccess: () => { setPendingAction(null); onRefresh(); } });
    }
  }

  const isMutating = promote.isPending || demote.isPending || resetFrivolous.isPending
    || freeze.isPending || unfreeze.isPending || resetDormancy.isPending || clearTerms.isPending;

  const modalProps = {
    promote: {
      title: "Promote to admin",
      description: `Grant ${user.displayName ?? user.username} admin privileges?`,
      confirmLabel: "Promote",
      confirmVariant: "primary" as const,
    },
    demote: {
      title: "Demote from admin",
      description: `Remove admin privileges from ${user.displayName ?? user.username}?`,
      confirmLabel: "Demote",
      confirmVariant: "destructive" as const,
    },
    resetFrivolous: {
      title: "Reset frivolous counter",
      description: `Reset the frivolous cancellation counter for ${user.displayName ?? user.username}?`,
      confirmLabel: "Reset",
      confirmVariant: "primary" as const,
    },
    freeze: {
      title: "Freeze wallet",
      description: `Block all wallet outflows (withdraw, pay-penalty, listing-fee, bid-reservation) for ${user.displayName ?? user.username}?`,
      confirmLabel: "Freeze",
      confirmVariant: "destructive" as const,
    },
    unfreeze: {
      title: "Unfreeze wallet",
      description: `Re-enable wallet outflows for ${user.displayName ?? user.username}?`,
      confirmLabel: "Unfreeze",
      confirmVariant: "primary" as const,
    },
    resetDormancy: {
      title: "Reset dormancy",
      description: `Clear the wallet dormancy state for ${user.displayName ?? user.username}?`,
      confirmLabel: "Reset",
      confirmVariant: "primary" as const,
    },
    clearTerms: {
      title: "Force terms re-acceptance",
      description: `Clear the wallet terms acceptance for ${user.displayName ?? user.username}? They'll be prompted to re-accept on their next visit.`,
      confirmLabel: "Clear terms",
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
        <div className="rounded-lg bg-danger-bg border border-danger/20 p-4 flex flex-col gap-2">
          <div className="text-[11px] font-semibold text-danger">Active ban</div>
          <div className="text-sm text-danger/80 line-clamp-3">
            {user.activeBan.reasonText}
          </div>
          {user.activeBan.expiresAt && (
            <div className="text-[11px] text-danger/70">
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
      <div className="rounded-lg bg-bg-muted border border-border-subtle p-4 flex flex-col gap-2">
        <div className="text-[11px] font-medium text-fg-muted mb-1">Role</div>

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

      {/* Wallet quick actions */}
      <div className="rounded-lg bg-bg-muted border border-border-subtle p-4 flex flex-col gap-2">
        <div className="text-[11px] font-medium text-fg-muted mb-1">Wallet</div>
        {wallet && (
          <div className="text-[11px] text-fg-muted flex flex-col gap-0.5 mb-1">
            <div className="flex justify-between">
              <span>Balance</span>
              <span className="font-mono text-fg">L$ {wallet.balanceLindens.toLocaleString()}</span>
            </div>
            <div className="flex justify-between">
              <span>Available</span>
              <span className="font-mono text-fg">L$ {wallet.availableLindens.toLocaleString()}</span>
            </div>
            {wallet.penaltyBalanceOwed > 0 && (
              <div className="flex justify-between">
                <span>Penalty owed</span>
                <span className="font-mono text-danger">L$ {wallet.penaltyBalanceOwed.toLocaleString()}</span>
              </div>
            )}
            {wallet.walletFrozenAt && (
              <div className="text-danger font-semibold mt-1">FROZEN</div>
            )}
          </div>
        )}
        <Button
          variant="secondary"
          size="sm"
          fullWidth
          onClick={() => setShowAdjustBalance(true)}
          data-testid="adjust-balance-btn"
        >
          Adjust balance
        </Button>
        {wallet && !wallet.walletFrozenAt && (
          <Button
            variant="destructive"
            size="sm"
            fullWidth
            onClick={() => setPendingAction("freeze")}
            data-testid="freeze-wallet-btn"
          >
            Freeze wallet
          </Button>
        )}
        {wallet && wallet.walletFrozenAt && (
          <Button
            variant="secondary"
            size="sm"
            fullWidth
            onClick={() => setPendingAction("unfreeze")}
            data-testid="unfreeze-wallet-btn"
          >
            Unfreeze wallet
          </Button>
        )}
        {wallet && wallet.penaltyBalanceOwed > 0 && (
          <Button
            variant="secondary"
            size="sm"
            fullWidth
            onClick={() => setShowForgivePenalty(true)}
            data-testid="forgive-penalty-btn"
          >
            Forgive penalty
          </Button>
        )}
        {wallet && wallet.walletDormancyPhase != null && (
          <Button
            variant="secondary"
            size="sm"
            fullWidth
            onClick={() => setPendingAction("resetDormancy")}
            data-testid="reset-dormancy-btn"
          >
            Reset dormancy
          </Button>
        )}
        <Button
          variant="secondary"
          size="sm"
          fullWidth
          onClick={() => setPendingAction("clearTerms")}
          data-testid="clear-terms-btn"
        >
          Force terms re-accept
        </Button>
      </div>

      {/* Frivolous counter */}
      {user.cancelledWithBids > 0 && (
        <div className="rounded-lg bg-bg-muted border border-border-subtle p-4 flex flex-col gap-2">
          <div className="text-[11px] font-medium text-fg-muted mb-1">
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
      <div className="rounded-lg bg-bg-muted border border-border-subtle p-4 flex flex-col gap-2">
        <div className="text-[11px] font-medium text-fg-muted mb-1">Bans</div>
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

      {/* Danger zone */}
      <div className="rounded-lg bg-bg-muted border border-danger/30 p-4 flex flex-col gap-2">
        <div className="text-[11px] font-medium text-danger mb-1">Danger zone</div>
        <Button
          variant="destructive"
          size="sm"
          fullWidth
          onClick={() => setShowDeleteUser(true)}
          data-testid="delete-user-btn"
        >
          Delete user
        </Button>
      </div>

      {/* Quick links */}
      <div className="rounded-lg bg-bg-muted border border-border-subtle p-4 flex flex-col gap-2">
        <div className="text-[11px] font-medium text-fg-muted mb-1">Quick links</div>
        <Link
          href={`/users/${user.publicId}`}
          className="text-sm text-brand hover:underline underline-offset-2"
          target="_blank"
          data-testid="public-profile-link"
        >
          Public profile
        </Link>
        {user.slAvatarUuid && (
          <button
            type="button"
            onClick={() => navigator.clipboard.writeText(user.slAvatarUuid!)}
            className="text-left text-sm text-fg-muted hover:text-fg transition-colors"
            data-testid="copy-uuid-btn"
          >
            Copy SL UUID
          </button>
        )}
        <button
          type="button"
          onClick={() => setShowIps(true)}
          className="text-left text-sm text-fg-muted hover:text-fg transition-colors"
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
        <RecentIpsModal publicId={user.publicId} onClose={() => setShowIps(false)} />
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

      {showDeleteUser && (
        <DeleteUserModal
          publicId={user.publicId}
          userLabel={user.displayName ?? user.username}
          onClose={() => setShowDeleteUser(false)}
        />
      )}

      <AdjustBalanceModal
        open={showAdjustBalance}
        publicId={user.publicId}
        balanceLindens={wallet?.balanceLindens ?? 0}
        reservedLindens={wallet?.reservedLindens ?? 0}
        onClose={() => setShowAdjustBalance(false)}
      />

      <ForgivePenaltyModal
        open={showForgivePenalty}
        publicId={user.publicId}
        penaltyOwed={wallet?.penaltyBalanceOwed ?? 0}
        onClose={() => setShowForgivePenalty(false)}
      />
    </aside>
  );
}
