import Link from "next/link";
import { Avatar } from "@/components/ui/Avatar";
import { Card } from "@/components/ui/Card";
import { StatusBadge } from "@/components/ui/StatusBadge";
import { cn } from "@/lib/cn";
import type { LeaderCardDto } from "@/types/realty";

export interface LeaderCardProps {
  leader: LeaderCardDto;
  className?: string;
}

/**
 * Card displaying a realty group's leader: avatar + display name +
 * "LEADER" badge, linking out to the user's public profile.
 *
 * Reused on the public group page and the dashboard manage view. The
 * leader is also rendered inside the agents grid under sub-project
 * surfaces; this dedicated card highlights leadership where the role
 * itself is the point (e.g. the "Leader" section on `/group/[slug]`).
 */
export function LeaderCard({ leader, className }: LeaderCardProps) {
  return (
    <Card className={cn("p-4", className)} data-testid="leader-card">
      <Link
        href={`/users/${encodeURIComponent(leader.userPublicId)}`}
        className="flex items-center gap-3 group"
      >
        <Avatar
          src={leader.avatarUrl ?? undefined}
          alt={leader.displayName}
          name={leader.displayName}
          size="lg"
        />
        <div className="flex flex-col gap-1 min-w-0">
          <span className="text-base font-semibold text-fg truncate group-hover:underline">
            {leader.displayName}
          </span>
          <StatusBadge tone="success" className="self-start">
            Leader
          </StatusBadge>
        </div>
      </Link>
    </Card>
  );
}
