"use client";

import { Card } from "@/components/ui/Card";
import { StatusBadge } from "@/components/ui/StatusBadge";
import { useUserRealtyGroups } from "@/hooks/realty/useRealtyGroups";
import type { UserRealtyGroupAffiliationDto } from "@/types/realty";
import { GroupChip } from "./GroupChip";

export interface ProfileGroupsSectionProps {
  userPublicId: string;
}

function roleLabel(role: UserRealtyGroupAffiliationDto["role"]): string {
  return role === "LEADER" ? "Leader" : "Agent";
}

/**
 * "Groups" section on the user public profile (spec §6.3). Renders the
 * caller's realty-group affiliations as a chip list, each chip paired
 * with a Leader / Agent badge.
 *
 * Hidden when:
 * - the query is pending or errored (no skeleton — keeps the profile
 *   clean if the public endpoint hiccups; matches `EditGroupAffordance`
 *   posture);
 * - the affiliations list is empty (heading does not render).
 *
 * Client component because {@link PublicProfileView} already runs on the
 * client; we follow the existing page pattern rather than introducing a
 * second SSR-vs-client split.
 */
export function ProfileGroupsSection({
  userPublicId,
}: ProfileGroupsSectionProps) {
  const { data, isPending, isError } = useUserRealtyGroups(userPublicId);

  if (isPending || isError) return null;
  if (!data || data.length === 0) return null;

  return (
    <Card data-testid="profile-groups-section">
      <Card.Header>
        <h2 className="text-sm font-semibold tracking-tight">Groups</h2>
      </Card.Header>
      <Card.Body>
        <ul className="flex flex-wrap gap-2">
          {data.map((affiliation) => (
            <li
              key={affiliation.groupPublicId}
              className="inline-flex items-center gap-2"
            >
              <GroupChip
                groupSlug={affiliation.groupSlug}
                groupName={affiliation.groupName}
                logoUrl={affiliation.logoLightUrl ?? affiliation.logoDarkUrl}
              />
              <StatusBadge
                tone={affiliation.role === "LEADER" ? "success" : "default"}
                data-testid={`profile-group-role-${affiliation.groupPublicId}`}
              >
                {roleLabel(affiliation.role)}
              </StatusBadge>
            </li>
          ))}
        </ul>
      </Card.Body>
    </Card>
  );
}
