"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { Button } from "@/components/ui/Button";
import { LoadingSpinner } from "@/components/ui/LoadingSpinner";
import { StatusBadge } from "@/components/ui/StatusBadge";
import { cn } from "@/lib/cn";
import { useAdminDissolveGroup, useAdminRealtyGroup } from "@/hooks/realty/useRealtyGroups";
import { RealtyGroupHeroBanner } from "@/components/realty/RealtyGroupHeroBanner";
import { AdminGroupProfileForm } from "./AdminGroupProfileForm";
import { AdminGroupMembersList } from "./AdminGroupMembersList";
import { AdminGroupSuspensionsTab } from "./AdminGroupSuspensionsTab";
import { AdminGroupBulkListingsTab } from "./AdminGroupBulkListingsTab";
import { AdminGroupReportsTab } from "./AdminGroupReportsTab";
import { AdminGroupSlGroupsTab } from "./AdminGroupSlGroupsTab";
import { AdminGroupAuditTab } from "./AdminGroupAuditTab";
import { AdminGroupWalletTab } from "./AdminGroupWalletTab";

type DetailTab =
  | "profile"
  | "members"
  | "wallet"
  | "suspensions"
  | "bulk-listings"
  | "reports"
  | "sl-groups"
  | "audit";

const TAB_LABELS: { id: DetailTab; label: string }[] = [
  { id: "profile", label: "Profile" },
  { id: "members", label: "Members" },
  { id: "wallet", label: "Wallet" },
  { id: "suspensions", label: "Suspensions" },
  { id: "bulk-listings", label: "Bulk listings" },
  { id: "reports", label: "Reports" },
  { id: "sl-groups", label: "SL groups" },
  { id: "audit", label: "Audit" },
];

export interface AdminRealtyGroupDetailPageProps {
  publicId: string;
}

/**
 * Detail surface for `/admin/realty-groups/[publicId]`. Renders the public
 * hero banner, an admin-edit profile form, a members list with admin
 * row actions (force-remove with replacement-leader picker for the leader),
 * an audit-log placeholder section, and a read-only invitations section.
 *
 * Backend gaps surfaced as placeholders:
 *
 *   - Audit log: a `targetType=REALTY_GROUP` filter exists on the generic
 *     {@code /api/v1/admin/audit-log} endpoint, but no per-group/per-id
 *     read endpoint. The section links to the global page filtered by
 *     this group's id. A richer per-group audit log surface ships with
 *     sub-project F (admin moderation).
 *
 *   - Invitations: {@code GET /realty-groups/{id}/invitations} requires
 *     {@code INVITE_AGENTS} on the caller. An admin without group
 *     membership cannot read invitations through this endpoint. A
 *     dedicated admin invitations read endpoint is deferred to F.
 */
export function AdminRealtyGroupDetailPage({
  publicId,
}: AdminRealtyGroupDetailPageProps) {
  const router = useRouter();
  const { data: group, isLoading, isError } = useAdminRealtyGroup(publicId);
  const dissolve = useAdminDissolveGroup();
  const [dissolveOpen, setDissolveOpen] = useState(false);
  const [dissolveText, setDissolveText] = useState("");
  const [activeTab, setActiveTab] = useState<DetailTab>("profile");

  if (isLoading) {
    return (
      <div data-testid="admin-realty-detail-loading">
        <LoadingSpinner label="Loading group..." />
      </div>
    );
  }

  if (isError || !group) {
    return (
      <div
        className="py-12 text-sm text-danger"
        data-testid="admin-realty-detail-error"
      >
        Could not load this group. Refresh to retry.
      </div>
    );
  }

  // The backend admin GET returns the same shape as the public GET, plus a
  // `dissolved` marker carried implicitly by `dissolvedAt` (not in the DTO
  // here). For now we treat the presence of the detail row as "exists",
  // and leave dissolved-state surfacing on the list-page status chip.

  function handleDissolveConfirm() {
    dissolve.mutate(publicId, {
      onSuccess: () => {
        setDissolveOpen(false);
        router.push("/admin/groups");
      },
    });
  }

  return (
    <div className="flex flex-col gap-6" data-testid="admin-realty-detail-page">
      <div className="flex items-start justify-between gap-3">
        <Link
          href="/admin/groups"
          className="text-xs text-fg-muted hover:text-fg"
          data-testid="admin-realty-back-link"
        >
          &larr; All realty groups
        </Link>
        <div className="flex items-center gap-2">
          <StatusBadge tone="success">Admin view</StatusBadge>
          <Button
            type="button"
            variant="destructive"
            size="sm"
            onClick={() => setDissolveOpen(true)}
            data-testid="admin-realty-detail-dissolve"
          >
            Force-dissolve
          </Button>
        </div>
      </div>

      <RealtyGroupHeroBanner
        name={group.name}
        slug={group.slug}
        description={group.description}
        website={group.website}
        memberSince={group.memberSince}
        memberCount={group.memberCount}
        coverUrl={group.coverUrl}
        logoUrl={group.logoUrl}
      />

      <nav
        role="tablist"
        aria-label="Realty group admin sections"
        className="flex gap-1 border-b border-border overflow-x-auto"
        data-testid="admin-realty-detail-tabs"
      >
        {TAB_LABELS.map((tab) => {
          const isActive = tab.id === activeTab;
          return (
            <button
              key={tab.id}
              type="button"
              role="tab"
              aria-selected={isActive}
              aria-controls={`admin-realty-tab-panel-${tab.id}`}
              onClick={() => setActiveTab(tab.id)}
              className={cn(
                "px-4 py-2 text-sm font-medium transition-colors whitespace-nowrap",
                isActive
                  ? "text-brand border-b-2 border-brand"
                  : "text-fg-muted hover:text-fg",
              )}
              data-testid={`admin-realty-tab-${tab.id}`}
            >
              {tab.label}
            </button>
          );
        })}
      </nav>

      <div
        role="tabpanel"
        id={`admin-realty-tab-panel-${activeTab}`}
        aria-labelledby={`admin-realty-tab-${activeTab}`}
        data-testid={`admin-realty-tab-panel-${activeTab}`}
      >
        {activeTab === "profile" && <AdminGroupProfileForm group={group} />}
        {activeTab === "members" && <AdminGroupMembersList group={group} />}
        {activeTab === "wallet" && (
          <AdminGroupWalletTab groupPublicId={group.publicId} />
        )}
        {activeTab === "suspensions" && (
          <AdminGroupSuspensionsTab groupPublicId={group.publicId} />
        )}
        {activeTab === "bulk-listings" && (
          <AdminGroupBulkListingsTab groupPublicId={group.publicId} />
        )}
        {activeTab === "reports" && (
          <AdminGroupReportsTab groupPublicId={group.publicId} />
        )}
        {activeTab === "sl-groups" && (
          <AdminGroupSlGroupsTab groupPublicId={group.publicId} />
        )}
        {activeTab === "audit" && (
          <AdminGroupAuditTab groupPublicId={group.publicId} />
        )}
      </div>

      <p
        className="text-xs text-fg-muted"
        data-testid="admin-realty-invitations-placeholder"
      >
        A read-only admin invitations surface is deferred. Leaders and
        {" "}
        {`{INVITE_AGENTS}`}-delegates can review live invitations from the
        manage page.
      </p>
      <Link
        href="/admin/audit-log"
        className="text-xs text-fg hover:underline"
        data-testid="admin-realty-audit-log-link"
      >
        Open global admin audit log &rarr;
      </Link>

      {dissolveOpen && (
        <div
          role="dialog"
          aria-modal="true"
          aria-label="Force-dissolve this group?"
          className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-scrim/40"
          onClick={() => setDissolveOpen(false)}
        >
          <div
            className="w-full max-w-md rounded-2xl bg-surface-raised p-6 shadow-md"
            onClick={(e) => e.stopPropagation()}
          >
            <h3 className="text-lg font-semibold text-fg mb-4">
              Force-dissolve this group?
            </h3>
            <div className="flex flex-col gap-3 text-sm">
              <p>
                This will dissolve <strong>{group.name}</strong> permanently.
                All members lose access. Existing listings are unlinked from
                the group.
              </p>
              <label className="flex flex-col gap-1 text-xs text-fg-muted">
                Type the group name to confirm
                <input
                  type="text"
                  value={dissolveText}
                  onChange={(e) => setDissolveText(e.target.value)}
                  className="rounded-lg bg-bg-subtle px-3 py-2 text-sm text-fg ring-1 ring-transparent focus:outline-none focus:ring-danger"
                  data-testid="admin-realty-detail-dissolve-input"
                  placeholder={group.name}
                />
              </label>
              <div className="flex justify-end gap-2 mt-2">
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
                  disabled={
                    dissolveText !== group.name || dissolve.isPending
                  }
                  loading={dissolve.isPending}
                  data-testid="admin-realty-detail-dissolve-confirm"
                >
                  Dissolve
                </Button>
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
