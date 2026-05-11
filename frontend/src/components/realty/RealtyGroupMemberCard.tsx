import Link from "next/link";
import { Avatar } from "@/components/ui/Avatar";
import { Card } from "@/components/ui/Card";
import { StatusBadge } from "@/components/ui/StatusBadge";
import { cn } from "@/lib/cn";
import { permissionLabel } from "@/lib/realty/permissions";
import type { AgentCardDto } from "@/types/realty";

export interface RealtyGroupMemberCardProps {
  member: AgentCardDto;
  className?: string;
}

/**
 * Render a relative-time string like "2 weeks ago" without pulling in a
 * date-fns dependency. The backend emits ISO strings; the rendering side
 * just needs a coarse bucket — exact minute granularity is not useful on
 * a "joined" line.
 */
function formatRelativeJoined(joinedAt: string): string {
  const then = new Date(joinedAt).getTime();
  if (Number.isNaN(then)) return "recently";
  const now = Date.now();
  const diffMs = Math.max(0, now - then);
  const day = 86_400_000;
  const days = Math.floor(diffMs / day);
  if (days < 1) return "today";
  if (days < 7) return `${days} day${days === 1 ? "" : "s"} ago`;
  const weeks = Math.floor(days / 7);
  if (weeks < 5) return `${weeks} week${weeks === 1 ? "" : "s"} ago`;
  const months = Math.floor(days / 30);
  if (months < 12) return `${months} month${months === 1 ? "" : "s"} ago`;
  const years = Math.floor(days / 365);
  return `${years} year${years === 1 ? "" : "s"} ago`;
}

/**
 * Single agent card used inside {@link RealtyGroupAgentsGrid} and the
 * dashboard's members tab.
 *
 * Visibility rules — the backend already strips `permissions` and
 * `joinedAt` from the DTO when the requester is not a group member, so
 * a `null` on either field means "this viewer does not see those
 * details". The component renders defensively: only show what we have.
 *
 * The card links to the user's public profile (`/users/{publicId}`).
 */
export function RealtyGroupMemberCard({
  member,
  className,
}: RealtyGroupMemberCardProps) {
  const isLeader = member.role === "LEADER";

  return (
    <Card className={cn("p-4", className)} data-testid="realty-group-member-card">
      <div className="flex items-start gap-3">
        <Link
          href={`/users/${encodeURIComponent(member.userPublicId)}`}
          className="shrink-0"
          aria-label={`View ${member.displayName}'s profile`}
        >
          <Avatar
            src={member.avatarUrl ?? undefined}
            alt={member.displayName}
            name={member.displayName}
            size="md"
          />
        </Link>
        <div className="flex flex-col gap-1.5 min-w-0 flex-1">
          <div className="flex items-center gap-2 flex-wrap">
            <Link
              href={`/users/${encodeURIComponent(member.userPublicId)}`}
              className="text-sm font-semibold text-fg truncate hover:underline"
            >
              {member.displayName}
            </Link>
            <StatusBadge tone={isLeader ? "success" : "default"}>
              {isLeader ? "Leader" : "Agent"}
            </StatusBadge>
          </div>
          {member.permissions && member.permissions.length > 0 && (
            <ul
              className="flex flex-wrap gap-1"
              aria-label="Permissions"
              data-testid="member-permissions"
            >
              {member.permissions.map((p) => (
                <li
                  key={p}
                  className="inline-flex items-center rounded bg-info-bg px-1.5 py-0.5 text-[11px] font-medium text-info"
                >
                  {permissionLabel(p)}
                </li>
              ))}
            </ul>
          )}
          {member.joinedAt && (
            <span className="text-xs text-fg-muted">
              Joined {formatRelativeJoined(member.joinedAt)}
            </span>
          )}
        </div>
      </div>
    </Card>
  );
}
