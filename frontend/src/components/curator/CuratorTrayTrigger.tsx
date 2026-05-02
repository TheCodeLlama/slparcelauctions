"use client";
import { Heart } from "@/components/ui/icons";
import { useAuth } from "@/lib/auth";
import { useSavedIds } from "@/hooks/useSavedAuctions";
import { cn } from "@/lib/cn";

export interface CuratorTrayTriggerProps {
  onOpen: () => void;
  className?: string;
}

/**
 * Heart + count badge in the top nav. Hidden entirely when the caller is
 * unauthenticated — the Curator Tray is a logged-in-only surface.
 *
 * <p>Count rendering:
 *   - {@code —} while the initial {@code useSavedIds} fetch is in flight.
 *   - Literal {@code 1}..{@code 99} for small sets.
 *   - {@code 99+} once the set has >= 100 entries.
 */
export function CuratorTrayTrigger({
  onOpen,
  className,
}: CuratorTrayTriggerProps) {
  const session = useAuth();
  const { ids, isLoading } = useSavedIds();

  if (session.status !== "authenticated") return null;

  const count = ids.size;
  const label = isLoading ? "—" : count >= 100 ? "99+" : String(count);

  return (
    <button
      type="button"
      onClick={onOpen}
      aria-label="Open Curator Tray"
      className={cn(
        "relative inline-flex items-center gap-1 rounded-full bg-bg-subtle px-3 py-1.5 text-xs font-medium text-fg",
        "hover:bg-bg-muted focus-visible:ring-2 focus-visible:ring-brand",
        className,
      )}
      data-testid="curator-tray-trigger"
    >
      <Heart className="size-4" aria-hidden="true" />
      <span data-testid="curator-tray-count">{label}</span>
    </button>
  );
}
