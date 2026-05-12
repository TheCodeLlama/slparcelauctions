"use client";

import { useMemo } from "react";
import { Card } from "@/components/ui/Card";
import { LoadingSpinner } from "@/components/ui/LoadingSpinner";
import { useRealtyGroupSlGroups } from "@/hooks/realty/useRealtyGroupSlGroups";
import type {
  AdminRealtyGroupSlGroup,
  RealtyGroupSlGroup,
} from "@/types/realty";
import { AdminSlGroupDriftRow } from "./AdminSlGroupDriftRow";

export interface AdminGroupSlGroupsTabProps {
  groupPublicId: string;
}

/**
 * Narrow the public {@link RealtyGroupSlGroup} shape to the admin
 * {@link AdminRealtyGroupSlGroup} shape, defaulting every admin-only
 * field to a safe null/zero. The backend admin GET-list endpoint for SL
 * groups is deferred (see Sub-project F spec); until it lands, the admin
 * tab reads the public list endpoint and the row component renders
 * gracefully against the available data.
 */
function toAdminRow(row: RealtyGroupSlGroup): AdminRealtyGroupSlGroup {
  return {
    publicId: row.publicId,
    slGroupUuid: row.slGroupUuid,
    slGroupName: row.slGroupName,
    verified: row.verified,
    verifiedAt: row.verifiedAt,
    verifiedVia: row.verifiedVia,
    founderAvatarUuid: row.founderAvatarUuid,
    currentFounderUuid: null,
    lastRevalidatedAt: null,
    consecutiveFetchFailures: 0,
    driftDetectedAt: null,
    driftReason: null,
    driftAcknowledgedAt: null,
    driftAcknowledgedByAdmin: null,
    unregisteredAt: null,
    unregisteredByAdmin: null,
    unregisterReason: null,
  };
}

/**
 * Realty Groups: F — admin "SL groups" tab on the group detail page.
 *
 * <p>Lists every SL group registration owned by this realty group and
 * surfaces the per-row admin actions (recheck, ack drift, force
 * unregister). Drift status is currently sourced from whatever the
 * underlying list endpoint exposes; admin-only drift fields default to
 * null until the dedicated admin list endpoint ships.
 */
export function AdminGroupSlGroupsTab({
  groupPublicId,
}: AdminGroupSlGroupsTabProps) {
  const { data, isLoading, isError } = useRealtyGroupSlGroups(groupPublicId);

  const rows = useMemo<AdminRealtyGroupSlGroup[]>(() => {
    if (!Array.isArray(data)) return [];
    return data.map(toAdminRow);
  }, [data]);

  return (
    <Card data-testid="admin-group-sl-groups-tab">
      <Card.Header>
        <h2 className="text-sm font-semibold tracking-tight">
          Linked SL groups
        </h2>
      </Card.Header>
      <Card.Body>
        {isLoading && (
          <div data-testid="admin-group-sl-groups-loading">
            <LoadingSpinner label="Loading SL group registrations…" />
          </div>
        )}
        {isError && (
          <p
            className="text-xs text-danger"
            data-testid="admin-group-sl-groups-error"
          >
            Could not load SL groups. Refresh to retry.
          </p>
        )}
        {!isLoading && !isError && rows.length === 0 && (
          <p
            className="text-xs text-fg-muted"
            data-testid="admin-group-sl-groups-empty"
          >
            This group has no SL group registrations.
          </p>
        )}
        {!isLoading && !isError && rows.length > 0 && (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="text-left text-xs text-fg-muted">
                  <th className="py-2 pr-3 font-medium">Status</th>
                  <th className="py-2 pr-3 font-medium">Group</th>
                  <th className="py-2 pr-3 font-medium">Founder</th>
                  <th className="py-2 pr-3 font-medium">Failures</th>
                  <th className="py-2 font-medium text-right">Actions</th>
                </tr>
              </thead>
              <tbody data-testid="admin-group-sl-groups-table">
                {rows.map((r) => (
                  <AdminSlGroupDriftRow
                    key={r.publicId}
                    groupPublicId={groupPublicId}
                    row={r}
                  />
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card.Body>
    </Card>
  );
}
