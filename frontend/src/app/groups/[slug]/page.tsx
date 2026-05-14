import type { Metadata } from "next";
import { notFound } from "next/navigation";
import { isApiError } from "@/lib/api";
import { realtyGroupsApi } from "@/lib/api/realtyGroups";
import { PublicGroupProfile } from "@/components/realty/browse/PublicGroupProfile";
import { RealtyGroupDissolvedView } from "@/components/realty/RealtyGroupDissolvedView";
import type { RealtyGroupPublicDto } from "@/types/realty";

/**
 * Public realty-group profile served from the new `/groups/[slug]` route
 * (the namespace migration replacing `/group/[slug]`). Server component;
 * anonymous-safe by construction (the underlying endpoint is `permitAll`),
 * so SSR fetches without a JWT just work. Logged-in member affordances
 * are layered on top via the {@link EditGroupAffordance} client component.
 *
 * Render at request time, never at build time. Static prerendering would
 * couple the Amplify build to whatever the backend happens to return at
 * build time; a bad-shape group payload would crash the build and block
 * every other page from deploying. Group membership, dissolution state,
 * and member rosters change per visit; a static snapshot is never the
 * right cache.
 */
export const dynamic = "force-dynamic";

interface PageProps {
  params: Promise<{ slug: string }>;
}

/**
 * Discriminated outcome of the server-side fetch. Each path renders a
 * different shell; splitting the resolution from the render keeps the
 * server component small and the error mapping local.
 */
type FetchOutcome =
  | { kind: "ok"; group: RealtyGroupPublicDto }
  | { kind: "dissolved"; name: string | null; dissolvedAt: string | null }
  | { kind: "notFound" };

async function fetchGroupBySlug(slug: string): Promise<FetchOutcome> {
  try {
    const group = await realtyGroupsApi.getGroupBySlug(slug);
    return { kind: "ok", group };
  } catch (err) {
    if (!isApiError(err)) throw err;
    if (err.status === 404) return { kind: "notFound" };
    if (err.status === 410) {
      // Backend may include last-known name + dissolvedAt on the problem
      // body (per spec — see RealtyGroupRepository
      // .findFirstBySlugAndDissolvedAtIsNotNullOrderByDissolvedAtDesc).
      // Read defensively: the keys are not guaranteed across versions.
      const problem = err.problem as Record<string, unknown>;
      const name =
        typeof problem.name === "string"
          ? problem.name
          : typeof problem.groupName === "string"
            ? (problem.groupName as string)
            : null;
      const dissolvedAt =
        typeof problem.dissolvedAt === "string"
          ? problem.dissolvedAt
          : null;
      return { kind: "dissolved", name, dissolvedAt };
    }
    throw err;
  }
}

export async function generateMetadata({ params }: PageProps): Promise<Metadata> {
  const { slug } = await params;
  if (!slug) return { title: "Realty group" };
  try {
    const outcome = await fetchGroupBySlug(slug);
    if (outcome.kind === "ok") {
      const description =
        outcome.group.description ?? "Realty group on SLParcels.";
      return {
        title: `${outcome.group.name} - Realty group`,
        description,
        openGraph: {
          title: outcome.group.name,
          description,
          type: "website",
        },
      };
    }
    if (outcome.kind === "dissolved") {
      return { title: outcome.name ?? "Dissolved realty group" };
    }
  } catch {
    // Fall through to the generic title; the page body will surface
    // the actual error via the Next error boundary.
  }
  return { title: "Realty group" };
}

/**
 * Public profile renders the template-style hero + 4-stat grid + tabbed
 * sections (Active listings / Members / Reviews / About) via
 * {@link PublicGroupProfile}. Member-only management lives at
 * {@code /groups/[slug]/manage/*} and is reached from the "Manage group"
 * button rendered on the profile when the viewer is a leader or agent.
 *
 * <p>The slug-keyed layout that previously wrapped this route was moved to
 * {@code /groups/[slug]/manage/layout.tsx} alongside the member-only sub-
 * pages; the public profile renders edge-to-edge so the template's hero +
 * 1280px max-width chrome can take over.
 */
export default async function RealtyGroupPublicPage({ params }: PageProps) {
  const { slug } = await params;
  if (!slug) notFound();

  const outcome = await fetchGroupBySlug(slug);

  if (outcome.kind === "notFound") notFound();
  if (outcome.kind === "dissolved") {
    return (
      <RealtyGroupDissolvedView
        name={outcome.name}
        dissolvedAt={outcome.dissolvedAt}
      />
    );
  }

  return <PublicGroupProfile group={outcome.group} />;
}
