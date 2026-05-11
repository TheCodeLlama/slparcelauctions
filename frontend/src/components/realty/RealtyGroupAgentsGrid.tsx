import { cn } from "@/lib/cn";
import type { AgentCardDto } from "@/types/realty";
import { RealtyGroupMemberCard } from "./RealtyGroupMemberCard";

export interface RealtyGroupAgentsGridProps {
  /**
   * Public roster as emitted by `RealtyGroupPublicDto.agents`. The
   * backend strips `permissions` and `joinedAt` for non-members so the
   * same array is safe to pass for anonymous and member callers.
   */
  agents: AgentCardDto[];
  /**
   * Defensive belt-and-braces: when false, suppress `permissions` and
   * `joinedAt` even if the backend somehow leaked them. Members visiting
   * the public URL still see the same skeleton as anonymous viewers in
   * Phase 13; the manage page handles member-only details.
   */
  viewerIsMember?: boolean;
  className?: string;
}

/**
 * Responsive grid of {@link RealtyGroupMemberCard}s. Renders one card
 * per agent in document order — the backend's ordering (typically
 * `joinedAt ASC`) is preserved.
 *
 * Layout: 1 column on mobile, 2 on tablet, 3 on desktop. The wrapping
 * page chrome (max-width container) constrains the absolute pixel size.
 */
export function RealtyGroupAgentsGrid({
  agents,
  viewerIsMember = false,
  className,
}: RealtyGroupAgentsGridProps) {
  return (
    <ul
      className={cn(
        "grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3",
        className,
      )}
      data-testid="realty-group-agents-grid"
    >
      {agents.map((agent) => {
        // Strip viewer-private fields defensively when the caller has
        // declared the viewer is anonymous. This shouldn't trigger in
        // practice (backend handles it), but the grid stays safe to
        // pass any DTO into.
        const visible: AgentCardDto = viewerIsMember
          ? agent
          : { ...agent, permissions: null, joinedAt: null };
        return (
          <li key={agent.memberPublicId}>
            <RealtyGroupMemberCard member={visible} />
          </li>
        );
      })}
    </ul>
  );
}
