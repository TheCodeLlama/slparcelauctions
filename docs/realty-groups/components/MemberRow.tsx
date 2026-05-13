// export/realty-groups/components/MemberRow.tsx
"use client";

import type { GroupMember } from "../types";
import { Avatar } from "./Avatar";
import { Badge } from "./Badge";
import { StarRating } from "./StarRating";
import { Btn } from "./Btn";

interface MemberRowProps {
  member: GroupMember;
  role: string;
}

export function MemberRow({ member, role }: MemberRowProps) {
  return (
    <div className="rounded-lg border border-border bg-surface-raised p-3.5 flex items-center gap-3.5">
      <Avatar name={member.name} size="lg" />
      <div className="flex-1 min-w-0">
        <div className="flex items-center gap-2">
          <span className="text-sm font-semibold">{member.name}</span>
          <Badge tone="neutral">{role}</Badge>
        </div>
        <div className="flex gap-2.5 items-center text-xs text-fg-muted mt-0.5">
          <StarRating value={member.rating} size={11} />
          <span>·</span>
          <span>{member.sales} sales</span>
          <span>·</span>
          <span>Member since {member.memberSince}</span>
        </div>
      </div>
      <Btn variant="ghost" size="sm">
        View profile
      </Btn>
    </div>
  );
}
