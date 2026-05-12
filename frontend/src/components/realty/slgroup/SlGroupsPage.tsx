"use client";

import { useState } from "react";
import { Button } from "@/components/ui/Button";
import { Card } from "@/components/ui/Card";
import { LoadingSpinner } from "@/components/ui/LoadingSpinner";
import { Plus, Users2 } from "@/components/ui/icons";
import { useRealtyGroup } from "@/hooks/realty/useRealtyGroups";
import { useRealtyGroupSlGroups } from "@/hooks/realty/useRealtyGroupSlGroups";
import { RegisterSlGroupModal } from "./RegisterSlGroupModal";
import { SlGroupListRow } from "./SlGroupListRow";

export interface SlGroupsPageProps {
  /** Realty group public UUID from the URL segment. */
  groupPublicId: string;
}

/**
 * Top-level SL group registration client component. Renders:
 *
 *  - Page header with realty group name and a "Register new SL group" CTA.
 *  - {@link RegisterSlGroupModal} — opens when the CTA fires.
 *  - Table of registered SL groups, one {@link SlGroupListRow} per row.
 *
 * Permission gating is enforced server-side: callers without
 * {@code REGISTER_SL_GROUP} get 403 on the mutation endpoints; the GET list
 * endpoint is open to any group member. Anonymous viewers (no realty-group
 * membership) get an empty/forbidden response which the UI surfaces as the
 * error block.
 *
 * Spec §6.2 + §6.6.
 */
export function SlGroupsPage({ groupPublicId }: SlGroupsPageProps) {
  const { data: group } = useRealtyGroup(groupPublicId);
  const { data: slGroups, isPending, error } =
    useRealtyGroupSlGroups(groupPublicId);
  const [registerOpen, setRegisterOpen] = useState(false);

  return (
    <div className="flex flex-col gap-6" data-testid="sl-groups-page">
      <div className="flex items-start justify-between gap-4">
        <div>
          <h1 className="text-xl font-bold tracking-tight font-display">
            SL Groups
          </h1>
          <p className="text-sm text-fg-muted mt-1">
            {group?.name
              ? `Register SL groups to ${group.name}. Verified SL groups can list parcels they own on SLParcels.`
              : "Register SL groups to this realty group. Verified SL groups can list parcels they own on SLParcels."}
          </p>
        </div>
        <Button
          variant="primary"
          size="md"
          leftIcon={<Plus className="h-4 w-4" />}
          onClick={() => setRegisterOpen(true)}
          data-testid="register-sl-group-button"
        >
          Register new SL group
        </Button>
      </div>

      <Card>
        <Card.Body>
          {isPending && (
            <div className="py-8 flex justify-center">
              <LoadingSpinner label="Loading SL groups..." />
            </div>
          )}

          {!isPending && error && (
            <div
              className="py-8 text-center text-sm text-fg-muted"
              data-testid="sl-groups-error"
            >
              {error instanceof Error
                ? error.message
                : "Failed to load SL groups."}
            </div>
          )}

          {!isPending && !error && slGroups && slGroups.length === 0 && (
            <div
              className="flex flex-col items-center text-center py-10 gap-3"
              data-testid="sl-groups-empty"
            >
              <Users2 className="h-12 w-12 text-fg-muted/40" aria-hidden="true" />
              <h3 className="font-medium text-fg">No SL groups registered yet</h3>
              <p className="text-sm text-fg-muted max-w-sm">
                Register an SL group to start listing parcels owned by it under
                this realty group.
              </p>
            </div>
          )}

          {!isPending && !error && slGroups && slGroups.length > 0 && (
            <div className="overflow-x-auto">
              <table className="w-full text-left" data-testid="sl-groups-table">
                <thead>
                  <tr className="text-xs uppercase tracking-wide text-fg-muted border-b border-border">
                    <th className="py-2 px-3 font-medium">Status</th>
                    <th className="py-2 px-3 font-medium">SL group</th>
                    <th className="py-2 px-3 font-medium">Verified via</th>
                    <th className="py-2 px-3 font-medium">Details</th>
                    <th className="py-2 px-3 font-medium text-right">Actions</th>
                  </tr>
                </thead>
                <tbody>
                  {slGroups.map((row) => (
                    <SlGroupListRow
                      key={row.publicId}
                      groupPublicId={groupPublicId}
                      row={row}
                    />
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </Card.Body>
      </Card>

      <RegisterSlGroupModal
        open={registerOpen}
        onClose={() => setRegisterOpen(false)}
        groupPublicId={groupPublicId}
      />
    </div>
  );
}
