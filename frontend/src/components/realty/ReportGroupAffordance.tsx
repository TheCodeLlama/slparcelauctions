"use client";

import { useState } from "react";
import { Button } from "@/components/ui/Button";
import { Flag } from "@/components/ui/icons";
import { useAuth } from "@/lib/auth";
import { useMyRealtyGroups } from "@/hooks/realty/useRealtyGroups";
import { ReportGroupModal } from "./ReportGroupModal";

export interface ReportGroupAffordanceProps {
  /** Public id of the group — passed straight into the modal/mutation. */
  groupPublicId: string;
  /** Slug — used to detect membership against {@link useMyRealtyGroups}. */
  groupSlug: string;
}

/**
 * "Report group" button + modal launcher, rendered next to other header
 * actions on the public group page.
 *
 * Rationale — the server component that renders {@code /group/[slug]} has
 * no JWT, so it cannot tell whether the visitor is authenticated or
 * already a member of the group. This thin client overlay layers that
 * gating on top: anonymous visitors and existing members see nothing;
 * everyone else gets the "Report group" button.
 *
 * Members include the leader (leaders appear in
 * {@link useMyRealtyGroups}'s response). Self-reports are pointless and
 * the backend rejects them with {@code 409 CANNOT_REPORT_OWN_GROUP}, so
 * suppressing the button for members is purely a UX nicety — the modal
 * itself still handles that error case if the affordance is ever shown
 * by mistake (e.g. cache lag).
 */
export function ReportGroupAffordance({
  groupPublicId,
  groupSlug,
}: ReportGroupAffordanceProps) {
  const session = useAuth();
  const myGroups = useMyRealtyGroups();
  const [open, setOpen] = useState(false);

  // Hide while we don't yet know the auth state — flipping the button on
  // mid-render once the bootstrap query resolves is jarring. Anonymous
  // visitors stay hidden permanently.
  if (session.status !== "authenticated") return null;

  // Wait for the my-groups query before rendering. Showing the button
  // and then yanking it after the affiliation list loads would also be
  // jarring; the visual cost of a one-tick delay is negligible.
  if (myGroups.isPending || myGroups.isError) return null;

  const isMember = (myGroups.data ?? []).some((g) => g.slug === groupSlug);
  if (isMember) return null;

  return (
    <>
      <Button
        type="button"
        size="sm"
        variant="secondary"
        leftIcon={<Flag className="size-4" aria-hidden="true" />}
        onClick={() => setOpen(true)}
        data-testid="report-group-button"
      >
        Report group
      </Button>
      <ReportGroupModal
        groupPublicId={groupPublicId}
        open={open}
        onClose={() => setOpen(false)}
      />
    </>
  );
}
