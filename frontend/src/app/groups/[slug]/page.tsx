import type { Metadata } from "next";
import { notFound } from "next/navigation";
import { isApiError } from "@/lib/api";
import { realtyGroupsApi } from "@/lib/api/realtyGroups";
import { SectionHeading } from "@/components/ui/SectionHeading";
import { EditGroupAffordance } from "@/components/realty/EditGroupAffordance";
import { LeaderCard } from "@/components/realty/LeaderCard";
import { RealtyGroupAgentsGrid } from "@/components/realty/RealtyGroupAgentsGrid";
import { RealtyGroupDissolvedView } from "@/components/realty/RealtyGroupDissolvedView";
import { RealtyGroupHeroBanner } from "@/components/realty/RealtyGroupHeroBanner";
import { ReportGroupAffordance } from "@/components/realty/ReportGroupAffordance";
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
 * Section composition: hero -> leader -> agents (when present). Reserved
 * sections for listings / sales / rating are intentionally absent until
 * later sub-projects land; there is no skeleton placeholder for them on
 * the public page.
 *
 * Note on layout: the slug-keyed layout (`./layout.tsx`) wraps every
 * `/groups/[slug]/*` route in a max-width container with a horizontal
 * sub-nav. The public profile is unique among siblings in that it
 * deliberately renders edge-to-edge for the hero banner; the `<main>`
 * inside the page handles its own max-width below the hero so the chrome
 * still looks consistent.
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

  const group = outcome.group;
  const agents = group.agents.filter(
    (a) => a.userPublicId !== group.leader.userPublicId,
  );

  return (
    <>
      <RealtyGroupHeroBanner
        name={group.name}
        slug={group.slug}
        description={group.description}
        website={group.website}
        memberSince={group.memberSince}
        memberCount={group.memberCount}
        coverUrl={group.coverUrl}
        logoUrl={group.logoUrl}
        editAffordance={<EditGroupAffordance slug={group.slug} />}
      />
      <main className="mx-auto w-full max-w-5xl px-4 sm:px-6 py-8 flex flex-col gap-10">
        <div className="flex justify-end">
          <ReportGroupAffordance
            groupPublicId={group.publicId}
            groupSlug={group.slug}
          />
        </div>
        <section aria-labelledby="leader-heading">
          <SectionHeading title={<span id="leader-heading">Leader</span>} />
          <LeaderCard leader={group.leader} />
        </section>

        {agents.length > 0 && (
          <section aria-labelledby="agents-heading">
            <SectionHeading
              title={<span id="agents-heading">Agents</span>}
              sub={`${agents.length} agent${agents.length === 1 ? "" : "s"}`}
            />
            <RealtyGroupAgentsGrid agents={agents} />
          </section>
        )}
      </main>
    </>
  );
}
