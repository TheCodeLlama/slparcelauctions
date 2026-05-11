import { Card } from "@/components/ui/Card";
import { EmptyState } from "@/components/ui/EmptyState";
import { AlertCircle } from "@/components/ui/icons";

export interface RealtyGroupDissolvedViewProps {
  /** Last known display name. Optional; backend may not return it on 410. */
  name?: string | null;
  /** ISO dissolution timestamp; optional for the same reason. */
  dissolvedAt?: string | null;
}

function formatDissolutionDate(dissolvedAt: string): string {
  const date = new Date(dissolvedAt);
  if (Number.isNaN(date.getTime())) return "an earlier date";
  return date.toLocaleDateString("en-US", {
    year: "numeric",
    month: "long",
    day: "numeric",
  });
}

/**
 * Rendered when `GET /api/v1/realty-groups/by-slug/{slug}` returns
 * HTTP 410 GROUP_DISSOLVED. The backend may or may not include the
 * group's last-known name and dissolution timestamp in the problem
 * body; the view degrades gracefully when those are absent.
 *
 * Distinct from {@link notFound} because a dissolved group existed at
 * this slug — the visitor's bookmark or link is not broken, the group
 * itself has simply wound down.
 */
export function RealtyGroupDissolvedView({
  name,
  dissolvedAt,
}: RealtyGroupDissolvedViewProps) {
  const headline = name
    ? `${name} has been dissolved`
    : "This realty group has been dissolved";
  const detail = dissolvedAt
    ? `Dissolved on ${formatDissolutionDate(dissolvedAt)}.`
    : "The group is no longer active.";
  return (
    <main className="mx-auto max-w-2xl px-4 py-12" data-testid="realty-group-dissolved-view">
      <Card>
        <Card.Body>
          <EmptyState icon={AlertCircle} headline={headline} description={detail} />
        </Card.Body>
      </Card>
    </main>
  );
}
