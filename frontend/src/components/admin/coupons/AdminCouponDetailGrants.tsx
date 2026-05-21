"use client";
import { useState } from "react";
import { Plus } from "@/components/ui/icons";
import { Button } from "@/components/ui/Button";
import { Pagination } from "@/components/ui/Pagination";
import { useAdminCouponGrants } from "@/hooks/admin/useAdminCouponGrants";
import { useRevokeCouponGrant } from "@/hooks/admin/useRevokeCouponGrant";
import type {
  CouponGrantDto,
  CouponGrantSource,
  CouponGrantState,
} from "@/types/coupon";
import { AdminCouponDirectGrantModal } from "./AdminCouponDirectGrantModal";

type Props = {
  couponPublicId: string;
};

const PAGE_SIZE = 25;

const STATE_LABEL: Record<CouponGrantState, string> = {
  ACTIVE: "Active",
  EXHAUSTED: "Exhausted",
  EXPIRED: "Expired",
  REVOKED: "Revoked",
};

const STATE_CLASS: Record<CouponGrantState, string> = {
  ACTIVE: "bg-success-bg text-success",
  EXHAUSTED: "bg-bg-muted text-fg-muted",
  EXPIRED: "bg-danger-bg text-danger",
  REVOKED: "bg-danger-bg text-danger",
};

const SOURCE_LABEL: Record<CouponGrantSource, string> = {
  REDEMPTION: "Redemption",
  ADMIN_GRANT: "Admin grant",
  SIGNUP_WINDOW: "Signup window",
};

function formatTimestamp(iso: string): string {
  return new Date(iso).toLocaleString("en-US", {
    month: "short",
    day: "numeric",
    year: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  });
}

function formatExpires(iso: string | null): string {
  if (!iso) return "(no expiry)";
  return new Date(iso).toLocaleString("en-US", {
    month: "short",
    day: "numeric",
    year: "numeric",
  });
}

function shortPublicId(id: string): string {
  return id.length <= 13 ? id : `${id.slice(0, 8)}...${id.slice(-4)}`;
}

function RevokeButton({
  grant,
  couponPublicId,
}: {
  grant: CouponGrantDto;
  couponPublicId: string;
}) {
  const revokeMutation = useRevokeCouponGrant(couponPublicId);
  const disabled =
    grant.state !== "ACTIVE" ||
    revokeMutation.isPending;

  function handleClick() {
    if (typeof window !== "undefined") {
      const ok = window.confirm(
        `Revoke this grant for user ${shortPublicId(grant.publicId)}? ` +
          `The discount disappears on the user's next listing.`,
      );
      if (!ok) return;
    }
    revokeMutation.mutate(grant.publicId);
  }

  if (grant.state !== "ACTIVE") {
    return <span className="text-[11px] text-fg-muted">--</span>;
  }

  return (
    <button
      type="button"
      onClick={handleClick}
      disabled={disabled}
      data-testid={`revoke-grant-${grant.publicId}`}
      className="text-[11px] text-danger hover:underline disabled:opacity-40 disabled:no-underline"
    >
      {revokeMutation.isPending ? "Revoking..." : "Revoke"}
    </button>
  );
}

export function AdminCouponDetailGrants({ couponPublicId }: Props) {
  const [stateFilter, setStateFilter] = useState<"" | CouponGrantState>("");
  const [sourceFilter, setSourceFilter] = useState<"" | CouponGrantSource>("");
  const [page, setPage] = useState(0);
  const [directOpen, setDirectOpen] = useState(false);

  const params = {
    state: stateFilter === "" ? undefined : stateFilter,
    source: sourceFilter === "" ? undefined : sourceFilter,
    page,
    size: PAGE_SIZE,
  };

  const { data, isLoading, isError } = useAdminCouponGrants(
    couponPublicId,
    params,
  );

  // We need the coupon code to label the modal. The detail wrapper has
  // it, but plumbing it through every tab as a prop is noisy. The
  // backend grant DTO carries it on every row, so pull from the first
  // returned grant; fall back to "this coupon" when the table is
  // empty (rare given users typically reach this UI after creating).
  const couponCode = data?.content[0]?.code ?? "this coupon";

  return (
    <div data-testid="coupon-grants-tab">
      <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
        <div className="flex flex-wrap items-center gap-3">
          <label className="flex items-center gap-2 text-xs text-fg-muted">
            State
            <select
              value={stateFilter}
              data-testid="grants-state-select"
              aria-label="Filter by grant state"
              onChange={(e) => {
                setStateFilter(e.target.value as "" | CouponGrantState);
                setPage(0);
              }}
              className="rounded-md bg-bg-muted px-2 py-1.5 text-sm text-fg ring-1 ring-border-subtle focus:outline-none focus:ring-2 focus:ring-brand"
            >
              <option value="">All</option>
              <option value="ACTIVE">Active</option>
              <option value="EXHAUSTED">Exhausted</option>
              <option value="EXPIRED">Expired</option>
              <option value="REVOKED">Revoked</option>
            </select>
          </label>
          <label className="flex items-center gap-2 text-xs text-fg-muted">
            Source
            <select
              value={sourceFilter}
              data-testid="grants-source-select"
              aria-label="Filter by grant source"
              onChange={(e) => {
                setSourceFilter(e.target.value as "" | CouponGrantSource);
                setPage(0);
              }}
              className="rounded-md bg-bg-muted px-2 py-1.5 text-sm text-fg ring-1 ring-border-subtle focus:outline-none focus:ring-2 focus:ring-brand"
            >
              <option value="">Any</option>
              <option value="REDEMPTION">Redemption</option>
              <option value="ADMIN_GRANT">Admin grant</option>
              <option value="SIGNUP_WINDOW">Signup window</option>
            </select>
          </label>
        </div>
        <Button
          variant="primary"
          size="sm"
          onClick={() => setDirectOpen(true)}
          leftIcon={<Plus className="size-4" />}
          data-testid="open-direct-grant-btn"
        >
          Direct grant
        </Button>
      </div>

      {isLoading && (
        <div className="py-8 text-sm text-fg-muted" aria-busy="true">
          Loading grants...
        </div>
      )}

      {isError && (
        <div className="py-8 text-sm text-danger">
          Could not load grants. Refresh to retry.
        </div>
      )}

      {data && data.content.length === 0 && (
        <div
          className="py-12 text-center text-sm text-fg-muted"
          data-testid="grants-empty"
        >
          No grants match these filters.
        </div>
      )}

      {data && data.content.length > 0 && (
        <>
          <div
            className="overflow-x-auto rounded-lg border border-border-subtle"
            data-testid="grants-table"
          >
            <table className="w-full text-sm">
              <thead className="bg-bg-subtle border-b border-border-subtle">
                <tr>
                  <th className="px-3 py-2.5 text-left text-[11px] font-medium text-fg-muted">
                    Grant ID
                  </th>
                  <th className="px-3 py-2.5 text-left text-[11px] font-medium text-fg-muted">
                    Granted
                  </th>
                  <th className="px-3 py-2.5 text-left text-[11px] font-medium text-fg-muted">
                    Expires
                  </th>
                  <th className="px-3 py-2.5 text-right text-[11px] font-medium text-fg-muted">
                    Remaining
                  </th>
                  <th className="px-3 py-2.5 text-left text-[11px] font-medium text-fg-muted">
                    State
                  </th>
                  <th className="px-3 py-2.5 text-left text-[11px] font-medium text-fg-muted">
                    Source
                  </th>
                  <th className="px-3 py-2.5 text-right text-[11px] font-medium text-fg-muted">
                    Actions
                  </th>
                </tr>
              </thead>
              <tbody>
                {data.content.map((g) => (
                  <tr
                    key={g.publicId}
                    data-testid={`grant-row-${g.publicId}`}
                    className="border-b border-border-subtle/50 hover:bg-bg-muted/50"
                  >
                    <td className="px-3 py-2.5 font-mono text-[11px] text-fg-muted">
                      {shortPublicId(g.publicId)}
                    </td>
                    <td className="px-3 py-2.5 text-fg-muted text-[11px]">
                      {formatTimestamp(g.grantedAt)}
                    </td>
                    <td className="px-3 py-2.5 text-fg-muted text-[11px]">
                      {formatExpires(g.expiresAt)}
                    </td>
                    <td className="px-3 py-2.5 text-right font-mono text-fg text-[11px]">
                      {g.remainingCount ?? "--"}
                    </td>
                    <td className="px-3 py-2.5">
                      <span
                        className={`inline-flex items-center px-2 py-0.5 rounded-full text-[10px] font-semibold ${STATE_CLASS[g.state]}`}
                      >
                        {STATE_LABEL[g.state]}
                      </span>
                    </td>
                    <td className="px-3 py-2.5 text-fg-muted text-[11px]">
                      {SOURCE_LABEL[g.source]}
                    </td>
                    <td className="px-3 py-2.5 text-right">
                      <RevokeButton
                        grant={g}
                        couponPublicId={couponPublicId}
                      />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          {data.totalPages > 1 && (
            <div className="mt-4">
              <Pagination
                page={data.number}
                totalPages={data.totalPages}
                onPageChange={setPage}
              />
            </div>
          )}
        </>
      )}

      <AdminCouponDirectGrantModal
        open={directOpen}
        couponPublicId={couponPublicId}
        couponCode={couponCode}
        onClose={() => setDirectOpen(false)}
      />
    </div>
  );
}
