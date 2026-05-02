import type { AdminUserDetail } from "@/lib/admin/types";

function formatDate(iso: string): string {
  return new Date(iso).toLocaleDateString("en-US", {
    month: "short",
    day: "numeric",
    year: "numeric",
  });
}

function truncateUuid(uuid: string): string {
  return uuid.length > 16 ? `${uuid.slice(0, 8)}…${uuid.slice(-4)}` : uuid;
}

type Props = {
  user: AdminUserDetail;
};

export function UserProfileHeader({ user }: Props) {
  return (
    <div className="mb-6 pb-6 border-b border-border-subtle" data-testid="user-profile-header">
      <div className="flex items-start gap-3 flex-wrap">
        <div className="flex-1 min-w-0">
          <div className="flex items-center gap-2 flex-wrap">
            <h1 className="text-2xl font-semibold text-fg truncate">
              {user.displayName ?? user.email}
            </h1>
            {user.activeBan && (
              <span
                data-testid="banned-chip"
                className="inline-flex items-center px-2 py-0.5 rounded-full text-[11px] font-semibold bg-danger-flat text-white"
              >
                BANNED
              </span>
            )}
            {user.role === "ADMIN" && (
              <span
                data-testid="admin-chip"
                className="inline-flex items-center px-2 py-0.5 rounded-full text-[11px] font-semibold bg-info-bg text-info-flat"
              >
                ADMIN
              </span>
            )}
            {user.verified ? (
              <span
                data-testid="verified-chip"
                className="inline-flex items-center px-2 py-0.5 rounded-full text-[11px] font-semibold bg-info-bg text-info-flat"
              >
                VERIFIED
              </span>
            ) : (
              <span
                data-testid="unverified-chip"
                className="inline-flex items-center px-2 py-0.5 rounded-full text-[11px] font-semibold bg-bg-hover text-fg-muted"
              >
                UNVERIFIED
              </span>
            )}
          </div>
          <div className="mt-1.5 text-sm text-fg-muted flex flex-wrap gap-x-4 gap-y-0.5">
            <span>{user.email}</span>
            {user.slAvatarUuid && (
              <span
                className="font-mono text-[11px]"
                title={user.slAvatarUuid}
              >
                {truncateUuid(user.slAvatarUuid)}
              </span>
            )}
            {user.slDisplayName && <span>{user.slDisplayName}</span>}
          </div>
        </div>
      </div>
      <div className="mt-2 text-[11px] text-fg-muted flex gap-4 flex-wrap">
        <span>Member since {formatDate(user.createdAt)}</span>
        {user.verifiedAt && (
          <span>Verified {formatDate(user.verifiedAt)}</span>
        )}
      </div>
    </div>
  );
}
