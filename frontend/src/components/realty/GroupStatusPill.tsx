import { cn } from "@/lib/cn";
import type { SuspensionStatus } from "@/types/realty";

export interface GroupStatusPillProps {
  /**
   * Suspension lifecycle status from the backend. {@code null} (or terminal
   * states {@code LIFTED} / {@code EXPIRED}) renders nothing — the caller can
   * mount this unconditionally and let the component decide whether the group
   * is currently under moderation.
   */
  status: SuspensionStatus | null;
  /**
   * ISO-8601 timestamp at which a timed suspension expires. Only used for
   * {@code ACTIVE_TIMED}; ignored otherwise. {@code null} for permanent bans.
   */
  expiresAt?: string | null;
  /**
   * Free-form reason / admin notes; surfaced as the {@code title} attribute
   * so it appears in the browser's default tooltip on hover. Omitted leaves
   * the pill without a tooltip.
   */
  reason?: string | null;
  className?: string;
}

/**
 * Format the expiry timestamp as "Mon DD, YYYY" — matches the listing-side
 * {@code SuspensionBanner} so the calendar-day boundary reads naturally.
 * Hour-precision adds noise; admins click into the suspensions tab for the
 * exact ISO timestamp.
 */
function formatExpiry(iso: string): string {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return "";
  return d.toLocaleDateString(undefined, {
    year: "numeric",
    month: "short",
    day: "numeric",
  });
}

/**
 * Inline pill that surfaces a realty group's current suspension status. Used
 * on the admin group detail header and the public group profile so any UI
 * touching a group can render the moderation state in one place.
 *
 * <ul>
 *   <li>{@code null} / {@code LIFTED} / {@code EXPIRED} — renders nothing
 *       (group is currently {@em active}).</li>
 *   <li>{@code ACTIVE_TIMED} — warning pill "Suspended until {date}".</li>
 *   <li>{@code ACTIVE_PERMANENT} — destructive pill "Banned".</li>
 * </ul>
 *
 * <p>The reason (admin notes from the suspension row) attaches as a
 * {@code title} tooltip — heavy hover-tooltip primitives aren't worth the
 * complexity for a status pill; the native tooltip works on desktop and
 * admins on mobile can click through to the suspensions tab.
 */
export function GroupStatusPill({
  status,
  expiresAt,
  reason,
  className,
}: GroupStatusPillProps) {
  if (status === null || status === "LIFTED" || status === "EXPIRED") {
    return null;
  }

  const baseClasses =
    "inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-medium";

  const tooltip = reason && reason.trim().length > 0 ? reason : undefined;

  if (status === "ACTIVE_PERMANENT") {
    return (
      <span
        className={cn(baseClasses, "bg-danger-bg text-danger", className)}
        data-testid="group-status-pill"
        data-variant="banned"
        title={tooltip}
      >
        Banned
      </span>
    );
  }

  // ACTIVE_TIMED
  const formatted = expiresAt ? formatExpiry(expiresAt) : "";
  const label = formatted ? `Suspended until ${formatted}` : "Suspended";

  return (
    <span
      className={cn(baseClasses, "bg-warning-bg text-warning", className)}
      data-testid="group-status-pill"
      data-variant="suspended"
      title={tooltip}
    >
      {label}
    </span>
  );
}
