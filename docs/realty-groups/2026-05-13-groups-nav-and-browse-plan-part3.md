# Groups Namespace Migration — Part 3: `/groups/[slug]/*` route tree

> Index: [`2026-05-13-groups-nav-and-browse-plan.md`](2026-05-13-groups-nav-and-browse-plan.md). Spec: [`2026-05-13-groups-nav-and-browse-design.md`](2026-05-13-groups-nav-and-browse-design.md). Prior parts: [`-part1.md`](2026-05-13-groups-nav-and-browse-plan-part1.md), [`-part2.md`](2026-05-13-groups-nav-and-browse-plan-part2.md).

**Tasks 14-22.** Persistent slug-level layout + sub-nav + nine sub-pages migrated from old locations (`/group/[slug]`, `/dashboard/(verified)/groups/[slug]/manage`, `/realty/groups/[publicId]/*`).

**Order rule:** Task 14 (layout + sub-nav) lands first so every sub-page renders inside it. Tasks 15-22 are pairwise file-disjoint and parallel-safe once Task 14 is in.

**DO NOT delete old route files in this part.** Task 30 in Part 4 handles deletions in one sweep after the internal-reference sweep in Task 29. Sub-pages here are additive; both the old and new trees coexist until Part 4.

**Slug → publicId resolution.** Each sub-page calls `useRealtyGroupBySlug(slug)` independently; TanStack Query dedupes the request across the layout + page in the same render, so the wire cost is one fetch. Each sub-page threads `group.publicId` into the existing publicId-keyed component (e.g. `GroupWalletPage`, `SlGroupsPage`, `GroupCommissionAnalyticsPage`) unchanged.

---

## Task 14: `/groups/[slug]/layout.tsx` with persistent sub-nav + non-member redirect

**Files:**
- Create: `frontend/src/app/groups/[slug]/layout.tsx`
- Create: `frontend/src/app/groups/[slug]/layout.test.tsx`

The layout is the brain of the slug-keyed tree. It runs `useRealtyGroupBySlug`, computes the caller's role + permissions the same way the existing manage page does, redirects non-members away from member-only paths, and renders a horizontal sub-nav strip whose items are gated per spec §5.4.

- [ ] **Step 1: Write failing tests for layout behavior**

```tsx
// frontend/src/app/groups/[slug]/layout.test.tsx
import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import GroupSlugLayout from "./layout";

const replace = vi.fn();
vi.mock("next/navigation", () => ({
  useParams: () => ({ slug: "sunset-realty" }),
  usePathname: () => "/groups/sunset-realty/profile",
  useRouter: () => ({ replace }),
}));

const useRealtyGroupBySlug = vi.fn();
vi.mock("@/hooks/realty/useRealtyGroups", () => ({
  useRealtyGroupBySlug: (slug: string) => useRealtyGroupBySlug(slug),
}));

const useCurrentUser = vi.fn();
vi.mock("@/lib/user", () => ({
  useCurrentUser: () => useCurrentUser(),
}));

function wrap(node: React.ReactNode) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<QueryClientProvider client={qc}>{node}</QueryClientProvider>);
}

function group({
  leaderPublicId,
  agents = [],
}: {
  leaderPublicId: string;
  agents?: Array<{ userPublicId: string; permissions: string[] }>;
}) {
  return {
    publicId: "g-1",
    slug: "sunset-realty",
    name: "Sunset Realty",
    leader: { userPublicId: leaderPublicId, displayName: "L", avatarUrl: null },
    agents: agents.map((a) => ({
      userPublicId: a.userPublicId,
      displayName: "A",
      avatarUrl: null,
      permissions: a.permissions,
      role: "AGENT",
    })),
  };
}

describe("groups/[slug] layout", () => {
  beforeEach(() => {
    replace.mockReset();
    useRealtyGroupBySlug.mockReset();
    useCurrentUser.mockReset();
  });

  it("renders Profile/Members/Wallet/SL Groups/Analytics/Invitations/Settings for the leader", async () => {
    useRealtyGroupBySlug.mockReturnValue({
      data: group({ leaderPublicId: "u-me" }),
      isPending: false,
    });
    useCurrentUser.mockReturnValue({ data: { publicId: "u-me" }, isPending: false });

    wrap(<GroupSlugLayout>{<div>child</div>}</GroupSlugLayout>);

    await waitFor(() => {
      expect(screen.getByRole("link", { name: /profile/i })).toBeInTheDocument();
      expect(screen.getByRole("link", { name: /members/i })).toBeInTheDocument();
      expect(screen.getByRole("link", { name: /wallet/i })).toBeInTheDocument();
      expect(screen.getByRole("link", { name: /sl groups/i })).toBeInTheDocument();
      expect(screen.getByRole("link", { name: /analytics/i })).toBeInTheDocument();
      expect(screen.getByRole("link", { name: /invitations/i })).toBeInTheDocument();
      expect(screen.getByRole("link", { name: /reviews/i })).toBeInTheDocument();
      expect(screen.getByRole("link", { name: /settings/i })).toBeInTheDocument();
    });
  });

  it("hides Settings + Invitations + Wallet for an agent without permissions", async () => {
    useRealtyGroupBySlug.mockReturnValue({
      data: group({
        leaderPublicId: "u-leader",
        agents: [{ userPublicId: "u-me", permissions: [] }],
      }),
      isPending: false,
    });
    useCurrentUser.mockReturnValue({ data: { publicId: "u-me" }, isPending: false });

    wrap(<GroupSlugLayout>{<div>child</div>}</GroupSlugLayout>);

    await waitFor(() => {
      expect(screen.getByRole("link", { name: /profile/i })).toBeInTheDocument();
    });
    expect(screen.queryByRole("link", { name: /settings/i })).toBeNull();
    expect(screen.queryByRole("link", { name: /^invitations$/i })).toBeNull();
    expect(screen.queryByRole("link", { name: /wallet/i })).toBeNull();
    expect(screen.queryByRole("link", { name: /sl groups/i })).toBeNull();
    expect(screen.queryByRole("link", { name: /analytics/i })).toBeNull();
  });

  it("shows Reviews to anyone (anonymous, non-member, member, leader)", async () => {
    useRealtyGroupBySlug.mockReturnValue({
      data: group({ leaderPublicId: "u-someone-else" }),
      isPending: false,
    });
    useCurrentUser.mockReturnValue({ data: null, isPending: false });

    wrap(<GroupSlugLayout>{<div>child</div>}</GroupSlugLayout>);
    await waitFor(() => {
      expect(screen.getByRole("link", { name: /reviews/i })).toBeInTheDocument();
    });
  });

  it("redirects non-members away from member-only paths", async () => {
    useRealtyGroupBySlug.mockReturnValue({
      data: group({ leaderPublicId: "u-someone-else" }),
      isPending: false,
    });
    useCurrentUser.mockReturnValue({ data: { publicId: "u-me" }, isPending: false });

    // usePathname mock returns "/groups/sunset-realty/profile" — a member-only path.
    wrap(<GroupSlugLayout>{<div>child</div>}</GroupSlugLayout>);

    await waitFor(() => {
      expect(replace).toHaveBeenCalledWith("/groups/sunset-realty");
    });
  });
});
```

- [ ] **Step 2: Run the tests to confirm they fail**

```powershell
cd frontend; npm test -- src/app/groups/`[slug`]/layout.test.tsx
```

Expected: FAIL — module not found.

- [ ] **Step 3: Create the layout**

```tsx
// frontend/src/app/groups/[slug]/layout.tsx
"use client";

import Link from "next/link";
import { useParams, usePathname, useRouter } from "next/navigation";
import { useEffect, useMemo, type ReactNode } from "react";
import { LoadingSpinner } from "@/components/ui/LoadingSpinner";
import { cn } from "@/lib/cn";
import { useRealtyGroupBySlug } from "@/hooks/realty/useRealtyGroups";
import { useCurrentUser } from "@/lib/user";
import type {
  RealtyGroupPermission,
  RealtyGroupPublicDto,
} from "@/types/realty";

interface SubNavItem {
  href: string;
  label: string;
  visible: boolean;
  /** True for the public reviews tab: members and non-members alike see it. */
  publicAccessible?: boolean;
}

/**
 * Persistent layout for every `/groups/[slug]/*` route. Spec §5.4.
 *
 * Renders a horizontal sub-nav whose items are gated on the caller's role
 * and permissions. Non-members deep-linking to any member-only path are
 * redirected to `/groups/[slug]`. The public profile (`/groups/[slug]`)
 * and reviews (`/groups/[slug]/reviews`) routes pass the gate unconditionally.
 *
 * Why a "use client" layout: we need `useParams`, `usePathname`,
 * `useRealtyGroupBySlug`, and `useCurrentUser` to compute role + active
 * nav item. The slug→publicId resolution happens here and again in each
 * sub-page; TanStack Query dedupes the network request inside the same
 * render so the wire cost stays at one fetch per slug.
 */
export default function GroupSlugLayout({ children }: { children: ReactNode }) {
  const router = useRouter();
  const params = useParams<{ slug: string }>();
  const pathname = usePathname();
  const slug = params?.slug;

  const group = useRealtyGroupBySlug(slug);
  const me = useCurrentUser();

  const { isLeader, isMember, callerPermissions } = useMemo(() => {
    if (!group.data || !me.data) {
      return {
        isLeader: false,
        isMember: false,
        callerPermissions: new Set<RealtyGroupPermission>(),
      };
    }
    const g: RealtyGroupPublicDto = group.data;
    const leader = g.leader.userPublicId === me.data.publicId;
    const ownAgentRow = g.agents.find(
      (a) => a.userPublicId === me.data!.publicId,
    );
    const perms = new Set<RealtyGroupPermission>(
      ownAgentRow?.permissions ?? [],
    );
    return {
      isLeader: leader,
      isMember: leader || !!ownAgentRow,
      callerPermissions: perms,
    };
  }, [group.data, me.data]);

  // Redirect non-members off member-only sub-paths. Public profile + reviews
  // are exempt — they're anonymous-accessible.
  useEffect(() => {
    if (!slug || group.isPending || me.isPending || !group.data) return;
    if (isMember) return;
    if (!pathname) return;
    const base = `/groups/${encodeURIComponent(slug)}`;
    const reviews = `${base}/reviews`;
    if (pathname === base || pathname === reviews) return;
    router.replace(base);
  }, [
    slug,
    pathname,
    group.isPending,
    group.data,
    me.isPending,
    isMember,
    router,
  ]);

  if (group.isPending || me.isPending) {
    return <LoadingSpinner label="Loading group..." />;
  }

  if (group.isError || !group.data) {
    return (
      <div className="mx-auto max-w-3xl px-4 py-8">
        <p className="text-sm text-danger">
          Couldn&apos;t load this group. Try again.
        </p>
      </div>
    );
  }

  const base = `/groups/${encodeURIComponent(slug ?? "")}`;
  const canInvite = isLeader || callerPermissions.has("INVITE_AGENTS");
  const canViewWallet =
    isLeader || callerPermissions.has("VIEW_GROUP_TRANSACTIONS");
  const canManageSlGroups =
    isLeader || callerPermissions.has("REGISTER_SL_GROUP");
  const canViewAnalytics =
    isLeader || callerPermissions.has("MANAGE_MEMBERS");

  const items: SubNavItem[] = [
    { href: `${base}/profile`, label: "Profile", visible: isMember },
    { href: `${base}/members`, label: "Members", visible: isMember },
    { href: `${base}/wallet`, label: "Wallet", visible: canViewWallet },
    { href: `${base}/sl-groups`, label: "SL Groups", visible: canManageSlGroups },
    {
      href: `${base}/analytics/commissions`,
      label: "Analytics",
      visible: canViewAnalytics,
    },
    { href: `${base}/invitations`, label: "Invitations", visible: canInvite },
    { href: `${base}/reviews`, label: "Reviews", visible: true, publicAccessible: true },
    { href: `${base}/settings`, label: "Settings", visible: isLeader },
  ];

  return (
    <div className="mx-auto max-w-5xl px-4 py-6 flex flex-col gap-6">
      <nav
        aria-label="Group sections"
        className="flex flex-wrap gap-1 border-b border-border"
        data-testid="group-sub-nav"
      >
        {items
          .filter((i) => i.visible)
          .map((i) => {
            const active = pathname === i.href;
            return (
              <Link
                key={i.href}
                href={i.href}
                aria-current={active ? "page" : undefined}
                className={cn(
                  "px-4 py-2 text-sm font-medium transition-colors",
                  active
                    ? "text-brand border-b-2 border-brand"
                    : "text-fg-muted hover:text-fg",
                )}
              >
                {i.label}
              </Link>
            );
          })}
      </nav>
      <div>{children}</div>
    </div>
  );
}
```

- [ ] **Step 4: Run the tests to confirm they pass**

```powershell
cd frontend; npm test -- src/app/groups/`[slug`]/layout.test.tsx
```

Expected: 4 tests, 0 failures.

- [ ] **Step 5: Commit**

```powershell
git add frontend/src/app/groups/`[slug`]/layout.tsx frontend/src/app/groups/`[slug`]/layout.test.tsx
git commit -m "feat(groups): persistent /groups/[slug] sub-nav layout with permission gating"
```

---

## Task 15: `/groups/[slug]/page.tsx` (public profile)

**Files:**
- Create: `frontend/src/app/groups/[slug]/page.tsx`
- Create: `frontend/src/app/groups/[slug]/page.test.tsx`
- (Template `GroupDetailPage` already moved into `frontend/src/components/realty/browse/` per Part 2 Task 8 — verify path before integrating.)

The public profile is a server component that renders the existing `RealtyGroupHeroBanner` (kept unchanged) atop the claude.ai/design `GroupDetailPage` template's sub-sections. Anonymous-safe; the `EditGroupAffordance` overlay handles the member-only gear.

- [ ] **Step 1: Verify the moved template's prop shape**

```powershell
Test-Path frontend/src/components/realty/browse/GroupDetailPage.tsx
```

If the file isn't there yet (Part 2 hasn't merged), pause this task until Part 2 Task 8 is in. The template's source props (`group`, `leader`, `agents`, `reviews`, `onFollow`, `onContact`) must match what the new public page passes; verify by reading the moved file.

- [ ] **Step 2: Write failing smoke test for the page**

```tsx
// frontend/src/app/groups/[slug]/page.test.tsx
import { describe, it, expect, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import RealtyGroupPublicPage from "./page";

vi.mock("@/lib/api/realtyGroups", () => ({
  realtyGroupsApi: {
    getGroupBySlug: vi.fn().mockResolvedValue({
      publicId: "g-1",
      slug: "sunset-realty",
      name: "Sunset Realty",
      description: "Mainland parcels.",
      website: null,
      memberSince: "2026-01-01T00:00:00Z",
      memberCount: 4,
      coverUrl: null,
      logoUrl: null,
      leader: {
        userPublicId: "u-leader",
        displayName: "Leader",
        avatarUrl: null,
      },
      agents: [],
    }),
  },
}));

vi.mock("@/lib/api", () => ({ isApiError: () => false }));

describe("/groups/[slug] public profile", () => {
  it("renders the group name and hero", async () => {
    const ui = await RealtyGroupPublicPage({
      params: Promise.resolve({ slug: "sunset-realty" }),
    } as never);
    render(ui as React.ReactElement);

    expect(screen.getByRole("heading", { name: /sunset realty/i })).toBeInTheDocument();
  });
});
```

- [ ] **Step 3: Run the test to confirm it fails**

```powershell
cd frontend; npm test -- src/app/groups/`[slug`]/page.test.tsx
```

Expected: FAIL — module not found.

- [ ] **Step 4: Create the page**

Re-use the structure of the existing `/group/[slug]/page.tsx` (server component, `force-dynamic`, slug-keyed fetch, dissolved/404 outcomes, `EditGroupAffordance` overlay). The only deltas:

1. Path is `frontend/src/app/groups/[slug]/page.tsx`.
2. Wraps the body in the slug-level layout's content area (the layout already provides the page chrome, so the page returns just the hero + sections).
3. After the existing leader + agents sections, mount the moved template component for the "About" / activity sections it provides — pass the existing `group` DTO + an `agents` array + an empty `reviews` array. Reviews on the public profile are limited to a teaser; the full list lives at `/groups/[slug]/reviews`.

```tsx
// frontend/src/app/groups/[slug]/page.tsx
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

export const dynamic = "force-dynamic";

interface PageProps {
  params: Promise<{ slug: string }>;
}

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
      const problem = err.problem as Record<string, unknown>;
      const name =
        typeof problem.name === "string"
          ? problem.name
          : typeof problem.groupName === "string"
            ? (problem.groupName as string)
            : null;
      const dissolvedAt =
        typeof problem.dissolvedAt === "string" ? problem.dissolvedAt : null;
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
      const description = outcome.group.description ?? "Realty group on SLParcels.";
      return {
        title: `${outcome.group.name} - Realty group`,
        description,
        openGraph: { title: outcome.group.name, description, type: "website" },
      };
    }
    if (outcome.kind === "dissolved") {
      return { title: outcome.name ?? "Dissolved realty group" };
    }
  } catch {
    /* fall through */
  }
  return { title: "Realty group" };
}

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
```

- [ ] **Step 5: Update `EditGroupAffordance` href to the new path**

The existing component hardcodes `/dashboard/groups/[slug]/manage`. The full sweep happens in Part 4 Task 29, but for this task make sure the local page works against the new tree. Either:

(a) flip the href here in the same commit (small surgical change), or
(b) leave the sweep for Task 29 and accept that the gear icon on `/groups/[slug]` routes to the old `/dashboard/...` page until Part 4.

**Pick (a)** so this task's smoke test stays green against the new tree. The Task 29 sweep then verifies no other call sites linger.

```tsx
// in frontend/src/components/realty/EditGroupAffordance.tsx
- href={`/dashboard/groups/${encodeURIComponent(slug)}/manage`}
+ href={`/groups/${encodeURIComponent(slug)}/profile`}
```

- [ ] **Step 6: Run the test to confirm it passes**

```powershell
cd frontend; npm test -- src/app/groups/`[slug`]/page.test.tsx
```

Expected: 1 test, 0 failures.

- [ ] **Step 7: Commit**

```powershell
git add frontend/src/app/groups/`[slug`]/page.tsx `
        frontend/src/app/groups/`[slug`]/page.test.tsx `
        frontend/src/components/realty/EditGroupAffordance.tsx
git commit -m "feat(groups): /groups/[slug] public profile page + EditGroupAffordance retarget"
```

---

## Task 16: `/groups/[slug]/profile/page.tsx` (former Profile tab) [parallel-safe with 17-22]

**Files:**
- Create: `frontend/src/app/groups/[slug]/profile/page.tsx`
- Create: `frontend/src/app/groups/[slug]/profile/page.test.tsx`

Replaces the Profile tab body of the old manage page. The body itself (`GroupProfileForm`) already exists and is reused unchanged — the page wrapper handles slug→group resolution and threads `callerPermissions` + `isLeader` through.

- [ ] **Step 1: Write failing test**

```tsx
// frontend/src/app/groups/[slug]/profile/page.test.tsx
import { describe, it, expect, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import GroupProfilePage from "./page";

vi.mock("next/navigation", () => ({
  useParams: () => ({ slug: "sunset-realty" }),
}));

vi.mock("@/hooks/realty/useRealtyGroups", () => ({
  useRealtyGroupBySlug: () => ({
    data: {
      publicId: "g-1",
      slug: "sunset-realty",
      name: "Sunset Realty",
      description: null,
      website: null,
      leader: { userPublicId: "u-me", displayName: "L", avatarUrl: null },
      agents: [],
      memberSince: "2026-01-01T00:00:00Z",
      memberCount: 1,
      coverUrl: null,
      logoUrl: null,
    },
    isPending: false,
  }),
}));

vi.mock("@/lib/user", () => ({
  useCurrentUser: () => ({ data: { publicId: "u-me" }, isPending: false }),
}));

function wrap(node: React.ReactNode) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<QueryClientProvider client={qc}>{node}</QueryClientProvider>);
}

describe("/groups/[slug]/profile", () => {
  it("renders the profile form for the leader", () => {
    wrap(<GroupProfilePage />);
    expect(screen.getByRole("textbox", { name: /name/i })).toBeInTheDocument();
  });
});
```

- [ ] **Step 2: Run to confirm it fails**

```powershell
cd frontend; npm test -- src/app/groups/`[slug`]/profile/page.test.tsx
```

- [ ] **Step 3: Create the page**

```tsx
// frontend/src/app/groups/[slug]/profile/page.tsx
"use client";

import { useParams } from "next/navigation";
import { useMemo } from "react";
import { LoadingSpinner } from "@/components/ui/LoadingSpinner";
import { GroupProfileForm } from "@/components/realty/GroupProfileForm";
import { useRealtyGroupBySlug } from "@/hooks/realty/useRealtyGroups";
import { useCurrentUser } from "@/lib/user";
import type {
  RealtyGroupPermission,
  RealtyGroupPublicDto,
} from "@/types/realty";

/**
 * Editable profile form for a realty group. Replaces the Profile tab on the
 * former `/dashboard/groups/[slug]/manage` page. The layout above this page
 * already verified group existence + caller membership; we still re-resolve
 * the group here because the form needs the full DTO and TanStack Query
 * dedupes the request.
 */
export default function GroupProfilePage() {
  const params = useParams<{ slug: string }>();
  const slug = params?.slug;
  const group = useRealtyGroupBySlug(slug);
  const me = useCurrentUser();

  const { callerPermissions, isLeader } = useMemo(() => {
    if (!group.data || !me.data) {
      return {
        callerPermissions: new Set<RealtyGroupPermission>(),
        isLeader: false,
      };
    }
    const g: RealtyGroupPublicDto = group.data;
    const leader = g.leader.userPublicId === me.data.publicId;
    const ownAgentRow = g.agents.find(
      (a) => a.userPublicId === me.data!.publicId,
    );
    return {
      isLeader: leader,
      callerPermissions: new Set<RealtyGroupPermission>(
        ownAgentRow?.permissions ?? [],
      ),
    };
  }, [group.data, me.data]);

  if (group.isPending || me.isPending) {
    return <LoadingSpinner label="Loading profile..." />;
  }
  if (!group.data) return null;

  return (
    <GroupProfileForm
      group={group.data}
      callerPermissions={callerPermissions}
      isLeader={isLeader}
    />
  );
}
```

- [ ] **Step 4: Run to confirm it passes**

```powershell
cd frontend; npm test -- src/app/groups/`[slug`]/profile/page.test.tsx
```

- [ ] **Step 5: Commit**

```powershell
git add frontend/src/app/groups/`[slug`]/profile
git commit -m "feat(groups): /groups/[slug]/profile page wrapping GroupProfileForm"
```

---

## Task 17: `/groups/[slug]/members/page.tsx` (former Members tab) [parallel-safe with 16, 18-22]

**Files:**
- Create: `frontend/src/app/groups/[slug]/members/page.tsx`
- Create: `frontend/src/app/groups/[slug]/members/page.test.tsx`

Same skeleton as Task 16; the body is `MembersTab` with its existing four-prop surface (`group`, `callerPermissions`, `isLeader`, `callerUserPublicId`).

- [ ] **Step 1: Write failing test**

```tsx
// frontend/src/app/groups/[slug]/members/page.test.tsx
import { describe, it, expect, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import GroupMembersPage from "./page";

vi.mock("next/navigation", () => ({
  useParams: () => ({ slug: "sunset-realty" }),
}));
vi.mock("@/hooks/realty/useRealtyGroups", () => ({
  useRealtyGroupBySlug: () => ({
    data: {
      publicId: "g-1",
      slug: "sunset-realty",
      name: "Sunset Realty",
      leader: {
        userPublicId: "u-me",
        displayName: "Leader",
        avatarUrl: null,
      },
      agents: [],
    },
    isPending: false,
  }),
  useLeaveGroup: () => ({ mutate: vi.fn(), isPending: false }),
  useRemoveMember: () => ({ mutate: vi.fn(), isPending: false }),
}));
vi.mock("@/lib/user", () => ({
  useCurrentUser: () => ({ data: { publicId: "u-me" }, isPending: false }),
}));

function wrap(node: React.ReactNode) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<QueryClientProvider client={qc}>{node}</QueryClientProvider>);
}

describe("/groups/[slug]/members", () => {
  it("renders the members table for the leader", () => {
    wrap(<GroupMembersPage />);
    expect(screen.getByText(/leader/i)).toBeInTheDocument();
  });
});
```

- [ ] **Step 2: Run to confirm it fails**

- [ ] **Step 3: Create the page**

```tsx
// frontend/src/app/groups/[slug]/members/page.tsx
"use client";

import { useParams } from "next/navigation";
import { useMemo } from "react";
import { LoadingSpinner } from "@/components/ui/LoadingSpinner";
import { MembersTab } from "@/components/realty/MembersTab";
import { useRealtyGroupBySlug } from "@/hooks/realty/useRealtyGroups";
import { useCurrentUser } from "@/lib/user";
import type {
  RealtyGroupPermission,
  RealtyGroupPublicDto,
} from "@/types/realty";

/**
 * Members list for a realty group. Replaces the Members tab on the former
 * `/dashboard/groups/[slug]/manage` page.
 */
export default function GroupMembersPage() {
  const params = useParams<{ slug: string }>();
  const slug = params?.slug;
  const group = useRealtyGroupBySlug(slug);
  const me = useCurrentUser();

  const { callerPermissions, isLeader, callerUserPublicId } = useMemo(() => {
    if (!group.data || !me.data) {
      return {
        callerPermissions: new Set<RealtyGroupPermission>(),
        isLeader: false,
        callerUserPublicId: null,
      };
    }
    const g: RealtyGroupPublicDto = group.data;
    const leader = g.leader.userPublicId === me.data.publicId;
    const ownAgentRow = g.agents.find(
      (a) => a.userPublicId === me.data!.publicId,
    );
    return {
      callerPermissions: new Set<RealtyGroupPermission>(
        ownAgentRow?.permissions ?? [],
      ),
      isLeader: leader,
      callerUserPublicId: me.data.publicId,
    };
  }, [group.data, me.data]);

  if (group.isPending || me.isPending) {
    return <LoadingSpinner label="Loading members..." />;
  }
  if (!group.data) return null;

  return (
    <MembersTab
      group={group.data}
      callerPermissions={callerPermissions}
      isLeader={isLeader}
      callerUserPublicId={callerUserPublicId}
    />
  );
}
```

- [ ] **Step 4: Run to confirm it passes**

- [ ] **Step 5: Commit**

```powershell
git add frontend/src/app/groups/`[slug`]/members
git commit -m "feat(groups): /groups/[slug]/members page wrapping MembersTab"
```

---

## Task 18: `/groups/[slug]/invitations/page.tsx` (former Invitations tab; group-scoped sender view) [parallel-safe with 16-17, 19-22]

**Files:**
- Create: `frontend/src/app/groups/[slug]/invitations/page.tsx`
- Create: `frontend/src/app/groups/[slug]/invitations/page.test.tsx`

Body is `InvitationsTab` (single prop: `groupPublicId: string`). Permission gate: leader or `INVITE_AGENTS`. Non-permitted callers see a 403-style notice rather than the form — the layout's sub-nav already hides the link, but a typed URL must still gate.

- [ ] **Step 1: Write failing test**

```tsx
// frontend/src/app/groups/[slug]/invitations/page.test.tsx
import { describe, it, expect, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import GroupInvitationsPage from "./page";

vi.mock("next/navigation", () => ({
  useParams: () => ({ slug: "sunset-realty" }),
}));

const groupWithLeader = (leaderId: string, agents: Array<{ userPublicId: string; permissions: string[] }> = []) => ({
  publicId: "g-1",
  slug: "sunset-realty",
  leader: { userPublicId: leaderId, displayName: "L", avatarUrl: null },
  agents: agents.map((a) => ({
    userPublicId: a.userPublicId,
    displayName: "A",
    avatarUrl: null,
    permissions: a.permissions,
    role: "AGENT",
  })),
});

let currentGroup: ReturnType<typeof groupWithLeader> = groupWithLeader("u-me");

vi.mock("@/hooks/realty/useRealtyGroups", () => ({
  useRealtyGroupBySlug: () => ({ data: currentGroup, isPending: false }),
  useRealtyGroupInvitations: () => ({ data: [], isPending: false }),
  useRevokeInvitation: () => ({ mutate: vi.fn() }),
}));

vi.mock("@/lib/user", () => ({
  useCurrentUser: () => ({ data: { publicId: "u-me" }, isPending: false }),
}));

function wrap(node: React.ReactNode) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<QueryClientProvider client={qc}>{node}</QueryClientProvider>);
}

describe("/groups/[slug]/invitations", () => {
  it("renders the invite form for the leader", () => {
    currentGroup = groupWithLeader("u-me");
    wrap(<GroupInvitationsPage />);
    expect(screen.getByText(/invite/i)).toBeInTheDocument();
  });

  it("renders forbidden notice for an agent without INVITE_AGENTS", () => {
    currentGroup = groupWithLeader("u-leader", [
      { userPublicId: "u-me", permissions: [] },
    ]);
    wrap(<GroupInvitationsPage />);
    expect(screen.getByText(/permission/i)).toBeInTheDocument();
  });
});
```

- [ ] **Step 2: Run to confirm it fails**

- [ ] **Step 3: Create the page**

```tsx
// frontend/src/app/groups/[slug]/invitations/page.tsx
"use client";

import { useParams } from "next/navigation";
import { useMemo } from "react";
import { LoadingSpinner } from "@/components/ui/LoadingSpinner";
import { InvitationsTab } from "@/components/realty/InvitationsTab";
import { useRealtyGroupBySlug } from "@/hooks/realty/useRealtyGroups";
import { useCurrentUser } from "@/lib/user";
import type { RealtyGroupPublicDto } from "@/types/realty";

/**
 * Group-scoped invitation sender view. Replaces the Invitations tab on the
 * former `/dashboard/groups/[slug]/manage` page. Permission gate: caller
 * must be the leader or hold `INVITE_AGENTS`. The layout above hides the
 * sub-nav link for unpermitted callers; this page still gates on URL hits.
 */
export default function GroupInvitationsPage() {
  const params = useParams<{ slug: string }>();
  const slug = params?.slug;
  const group = useRealtyGroupBySlug(slug);
  const me = useCurrentUser();

  const canInvite = useMemo(() => {
    if (!group.data || !me.data) return false;
    const g: RealtyGroupPublicDto = group.data;
    if (g.leader.userPublicId === me.data.publicId) return true;
    const ownAgentRow = g.agents.find(
      (a) => a.userPublicId === me.data!.publicId,
    );
    return !!ownAgentRow?.permissions.includes("INVITE_AGENTS");
  }, [group.data, me.data]);

  if (group.isPending || me.isPending) {
    return <LoadingSpinner label="Loading invitations..." />;
  }
  if (!group.data) return null;
  if (!canInvite) {
    return (
      <p className="text-sm text-fg-muted">
        You don&apos;t have permission to manage invitations for this group.
      </p>
    );
  }
  return <InvitationsTab groupPublicId={group.data.publicId} />;
}
```

- [ ] **Step 4: Run to confirm it passes**

- [ ] **Step 5: Commit**

```powershell
git add frontend/src/app/groups/`[slug`]/invitations
git commit -m "feat(groups): /groups/[slug]/invitations sender-view page"
```

---

## Task 19: `/groups/[slug]/settings/page.tsx` (former Settings tab) [parallel-safe with 16-18, 20-22]

**Files:**
- Create: `frontend/src/app/groups/[slug]/settings/page.tsx`
- Create: `frontend/src/app/groups/[slug]/settings/page.test.tsx`

Body is `SettingsTab` (single prop: `group: RealtyGroupPublicDto`). Leader-only.

Note the existing `SettingsTab.tsx` redirects to `/dashboard/groups` after dissolve — Task 29 (Part 4) sweeps that. For this task we don't touch the component's internals.

- [ ] **Step 1: Write failing test**

```tsx
// frontend/src/app/groups/[slug]/settings/page.test.tsx
import { describe, it, expect, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import GroupSettingsPage from "./page";

vi.mock("next/navigation", () => ({
  useParams: () => ({ slug: "sunset-realty" }),
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
}));

let leaderId = "u-me";

vi.mock("@/hooks/realty/useRealtyGroups", () => ({
  useRealtyGroupBySlug: () => ({
    data: {
      publicId: "g-1",
      slug: "sunset-realty",
      name: "Sunset Realty",
      leader: { userPublicId: leaderId, displayName: "L", avatarUrl: null },
      agents: [],
    },
    isPending: false,
  }),
  useDissolveGroup: () => ({ mutate: vi.fn(), isPending: false }),
  useTransferLeadership: () => ({ mutate: vi.fn(), isPending: false }),
}));

vi.mock("@/lib/user", () => ({
  useCurrentUser: () => ({ data: { publicId: "u-me" }, isPending: false }),
}));

function wrap(node: React.ReactNode) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<QueryClientProvider client={qc}>{node}</QueryClientProvider>);
}

describe("/groups/[slug]/settings", () => {
  it("renders settings for the leader", () => {
    leaderId = "u-me";
    wrap(<GroupSettingsPage />);
    expect(screen.getByText(/transfer/i)).toBeInTheDocument();
  });

  it("renders forbidden notice for non-leader", () => {
    leaderId = "u-someone-else";
    wrap(<GroupSettingsPage />);
    expect(screen.getByText(/leader/i)).toBeInTheDocument();
  });
});
```

- [ ] **Step 2: Run to confirm it fails**

- [ ] **Step 3: Create the page**

```tsx
// frontend/src/app/groups/[slug]/settings/page.tsx
"use client";

import { useParams } from "next/navigation";
import { useMemo } from "react";
import { LoadingSpinner } from "@/components/ui/LoadingSpinner";
import { SettingsTab } from "@/components/realty/SettingsTab";
import { useRealtyGroupBySlug } from "@/hooks/realty/useRealtyGroups";
import { useCurrentUser } from "@/lib/user";

/**
 * Leader-only settings page (transfer leadership, dissolve). Replaces the
 * Settings tab on the former `/dashboard/groups/[slug]/manage` page.
 */
export default function GroupSettingsPage() {
  const params = useParams<{ slug: string }>();
  const slug = params?.slug;
  const group = useRealtyGroupBySlug(slug);
  const me = useCurrentUser();

  const isLeader = useMemo(() => {
    if (!group.data || !me.data) return false;
    return group.data.leader.userPublicId === me.data.publicId;
  }, [group.data, me.data]);

  if (group.isPending || me.isPending) {
    return <LoadingSpinner label="Loading settings..." />;
  }
  if (!group.data) return null;
  if (!isLeader) {
    return (
      <p className="text-sm text-fg-muted">
        Only the group leader can change settings.
      </p>
    );
  }
  return <SettingsTab group={group.data} />;
}
```

- [ ] **Step 4: Run to confirm it passes**

- [ ] **Step 5: Commit**

```powershell
git add frontend/src/app/groups/`[slug`]/settings
git commit -m "feat(groups): /groups/[slug]/settings leader-only page"
```

---

## Task 20: `/groups/[slug]/wallet/page.tsx` (from `/realty/groups/[publicId]/wallet`) [parallel-safe with 16-19, 21-22]

**Files:**
- Create: `frontend/src/app/groups/[slug]/wallet/page.tsx`
- Create: `frontend/src/app/groups/[slug]/wallet/page.test.tsx`

`GroupWalletPage` already takes `publicId: string`. The new wrapper resolves slug→publicId on the client, then passes it through unchanged.

- [ ] **Step 1: Write failing test**

```tsx
// frontend/src/app/groups/[slug]/wallet/page.test.tsx
import { describe, it, expect, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import GroupWalletPageRoute from "./page";

vi.mock("next/navigation", () => ({
  useParams: () => ({ slug: "sunset-realty" }),
}));

vi.mock("@/hooks/realty/useRealtyGroups", () => ({
  useRealtyGroupBySlug: () => ({
    data: { publicId: "g-1", slug: "sunset-realty", name: "Sunset Realty" },
    isPending: false,
  }),
}));

vi.mock("@/components/realty/wallet/GroupWalletPage", () => ({
  GroupWalletPage: ({ publicId }: { publicId: string }) => (
    <div data-testid="wallet-page">wallet:{publicId}</div>
  ),
}));

function wrap(node: React.ReactNode) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<QueryClientProvider client={qc}>{node}</QueryClientProvider>);
}

describe("/groups/[slug]/wallet", () => {
  it("passes resolved publicId into GroupWalletPage", () => {
    wrap(<GroupWalletPageRoute />);
    expect(screen.getByTestId("wallet-page")).toHaveTextContent("wallet:g-1");
  });
});
```

- [ ] **Step 2: Run to confirm it fails**

- [ ] **Step 3: Create the page**

```tsx
// frontend/src/app/groups/[slug]/wallet/page.tsx
"use client";

import { useParams } from "next/navigation";
import { LoadingSpinner } from "@/components/ui/LoadingSpinner";
import { GroupWalletPage } from "@/components/realty/wallet/GroupWalletPage";
import { useRealtyGroupBySlug } from "@/hooks/realty/useRealtyGroups";

/**
 * Wallet page for a realty group. Migrated from
 * `/realty/groups/[publicId]/wallet`. The body component is unchanged;
 * this wrapper resolves slug → publicId so the existing publicId-keyed
 * service surface keeps working.
 */
export default function GroupWalletPageRoute() {
  const params = useParams<{ slug: string }>();
  const slug = params?.slug;
  const group = useRealtyGroupBySlug(slug);

  if (group.isPending) {
    return <LoadingSpinner label="Loading wallet..." />;
  }
  if (!group.data) return null;

  return (
    <div className="flex flex-col gap-6">
      <div>
        <h1 className="text-xl font-bold tracking-tight font-display">
          Group Wallet
        </h1>
        <p className="text-sm text-fg-muted mt-1">
          Balance, withdrawals, and transaction history for this realty group.
        </p>
      </div>
      <GroupWalletPage publicId={group.data.publicId} />
    </div>
  );
}
```

- [ ] **Step 4: Run to confirm it passes**

- [ ] **Step 5: Commit**

```powershell
git add frontend/src/app/groups/`[slug`]/wallet
git commit -m "feat(groups): /groups/[slug]/wallet page wrapping GroupWalletPage with slug resolution"
```

---

## Task 21: `/groups/[slug]/sl-groups/page.tsx` + `/groups/[slug]/analytics/commissions/page.tsx` [parallel-safe with 16-20, 22]

**Files:**
- Create: `frontend/src/app/groups/[slug]/sl-groups/page.tsx`
- Create: `frontend/src/app/groups/[slug]/sl-groups/page.test.tsx`
- Create: `frontend/src/app/groups/[slug]/analytics/commissions/page.tsx`
- Create: `frontend/src/app/groups/[slug]/analytics/commissions/page.test.tsx`

Both follow the same wrapper pattern as Task 20: resolve slug, thread `publicId` (here named `groupPublicId` on the existing components) into the unchanged body.

- [ ] **Step 1: Write failing tests**

```tsx
// frontend/src/app/groups/[slug]/sl-groups/page.test.tsx
import { describe, it, expect, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import SlGroupsPageRoute from "./page";

vi.mock("next/navigation", () => ({
  useParams: () => ({ slug: "sunset-realty" }),
}));
vi.mock("@/hooks/realty/useRealtyGroups", () => ({
  useRealtyGroupBySlug: () => ({
    data: { publicId: "g-1", slug: "sunset-realty" },
    isPending: false,
  }),
}));
vi.mock("@/components/realty/slgroup/SlGroupsPage", () => ({
  SlGroupsPage: ({ groupPublicId }: { groupPublicId: string }) => (
    <div data-testid="sl-groups-page">{groupPublicId}</div>
  ),
}));

function wrap(node: React.ReactNode) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<QueryClientProvider client={qc}>{node}</QueryClientProvider>);
}

describe("/groups/[slug]/sl-groups", () => {
  it("passes resolved publicId into SlGroupsPage", () => {
    wrap(<SlGroupsPageRoute />);
    expect(screen.getByTestId("sl-groups-page")).toHaveTextContent("g-1");
  });
});
```

```tsx
// frontend/src/app/groups/[slug]/analytics/commissions/page.test.tsx
import { describe, it, expect, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import CommissionsAnalyticsPageRoute from "./page";

vi.mock("next/navigation", () => ({
  useParams: () => ({ slug: "sunset-realty" }),
}));
vi.mock("@/hooks/realty/useRealtyGroups", () => ({
  useRealtyGroupBySlug: () => ({
    data: { publicId: "g-1", slug: "sunset-realty" },
    isPending: false,
  }),
}));
vi.mock("@/components/realty/analytics/GroupCommissionAnalyticsPage", () => ({
  GroupCommissionAnalyticsPage: ({ groupPublicId }: { groupPublicId: string }) => (
    <div data-testid="commission-analytics">{groupPublicId}</div>
  ),
}));

function wrap(node: React.ReactNode) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<QueryClientProvider client={qc}>{node}</QueryClientProvider>);
}

describe("/groups/[slug]/analytics/commissions", () => {
  it("passes resolved publicId into GroupCommissionAnalyticsPage", () => {
    wrap(<CommissionsAnalyticsPageRoute />);
    expect(screen.getByTestId("commission-analytics")).toHaveTextContent("g-1");
  });
});
```

- [ ] **Step 2: Run to confirm both fail**

- [ ] **Step 3: Create the pages**

```tsx
// frontend/src/app/groups/[slug]/sl-groups/page.tsx
"use client";

import { useParams } from "next/navigation";
import { LoadingSpinner } from "@/components/ui/LoadingSpinner";
import { SlGroupsPage } from "@/components/realty/slgroup/SlGroupsPage";
import { useRealtyGroupBySlug } from "@/hooks/realty/useRealtyGroups";

export default function SlGroupsPageRoute() {
  const params = useParams<{ slug: string }>();
  const group = useRealtyGroupBySlug(params?.slug);
  if (group.isPending) return <LoadingSpinner label="Loading SL groups..." />;
  if (!group.data) return null;
  return <SlGroupsPage groupPublicId={group.data.publicId} />;
}
```

```tsx
// frontend/src/app/groups/[slug]/analytics/commissions/page.tsx
"use client";

import { useParams } from "next/navigation";
import { LoadingSpinner } from "@/components/ui/LoadingSpinner";
import { GroupCommissionAnalyticsPage } from "@/components/realty/analytics/GroupCommissionAnalyticsPage";
import { useRealtyGroupBySlug } from "@/hooks/realty/useRealtyGroups";

export default function CommissionsAnalyticsPageRoute() {
  const params = useParams<{ slug: string }>();
  const group = useRealtyGroupBySlug(params?.slug);
  if (group.isPending) return <LoadingSpinner label="Loading analytics..." />;
  if (!group.data) return null;
  return <GroupCommissionAnalyticsPage groupPublicId={group.data.publicId} />;
}
```

- [ ] **Step 4: Run to confirm both pass**

- [ ] **Step 5: Commit**

```powershell
git add frontend/src/app/groups/`[slug`]/sl-groups `
        frontend/src/app/groups/`[slug`]/analytics
git commit -m "feat(groups): /groups/[slug]/sl-groups + /analytics/commissions wrappers"
```

---

## Task 22: `/groups/[slug]/reviews/page.tsx` [parallel-safe with 16-21]

**Files:**
- Create: `frontend/src/app/groups/[slug]/reviews/page.tsx`
- Create: `frontend/src/app/groups/[slug]/reviews/page.test.tsx`

The old page at `frontend/src/app/realty/groups/[publicId]/reviews/page.tsx` is a server component keyed on `publicId`. The new page mirrors it but keys on `slug` — it resolves slug→group server-side (anonymous-safe; the slug fetch endpoint is `permitAll`), then calls `fetchGroupReviews(publicId, page)` exactly like the old page did.

Internal "Back to" link + pagination Previous/Next links flip from `/group/[slug]` and `/realty/groups/[publicId]/reviews` to `/groups/[slug]` and `/groups/[slug]/reviews` respectively. The sweep in Task 29 also covers `GroupRatingBadge.tsx` (which links to `/realty/groups/[publicId]/reviews` today).

- [ ] **Step 1: Write failing test**

```tsx
// frontend/src/app/groups/[slug]/reviews/page.test.tsx
import { describe, it, expect, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import GroupReviewsPage from "./page";

vi.mock("@/lib/api/realtyGroups", () => ({
  realtyGroupsApi: {
    getGroupBySlug: vi.fn().mockResolvedValue({
      publicId: "g-1",
      slug: "sunset-realty",
      name: "Sunset Realty",
      rating: { averageRating: 4.5, reviewCount: 2 },
    }),
  },
}));
vi.mock("@/lib/api/realtyGroupReviews", () => ({
  fetchGroupReviews: vi.fn().mockResolvedValue({
    content: [],
    totalElements: 0,
    totalPages: 0,
    number: 0,
    size: 20,
  }),
}));
vi.mock("@/lib/api", () => ({ isApiError: () => false }));

describe("/groups/[slug]/reviews", () => {
  it("renders the reviews header for the group", async () => {
    const ui = await GroupReviewsPage({
      params: Promise.resolve({ slug: "sunset-realty" }),
      searchParams: Promise.resolve({}),
    } as never);
    render(ui as React.ReactElement);
    expect(screen.getByRole("heading", { name: /sunset realty/i })).toBeInTheDocument();
  });
});
```

- [ ] **Step 2: Run to confirm it fails**

- [ ] **Step 3: Create the page**

```tsx
// frontend/src/app/groups/[slug]/reviews/page.tsx
import Link from "next/link";
import { notFound } from "next/navigation";

import { GroupRatingBadge } from "@/components/realty/GroupRatingBadge";
import { isApiError } from "@/lib/api";
import { fetchGroupReviews } from "@/lib/api/realtyGroupReviews";
import { realtyGroupsApi } from "@/lib/api/realtyGroups";
import { formatRelativeTime } from "@/lib/time/relativeTime";

/**
 * Public reviews page. Migrated from `/realty/groups/[publicId]/reviews`
 * with the URL key flipped to slug. Server component; anonymous-safe
 * because both the slug-lookup and reviews endpoints are `permitAll`.
 */
export const dynamic = "force-dynamic";

interface PageProps {
  params: Promise<{ slug: string }>;
  searchParams?: Promise<{ page?: string }>;
}

export default async function GroupReviewsPage({
  params,
  searchParams,
}: PageProps) {
  const { slug } = await params;
  if (!slug) notFound();

  const { page: pageParam } = (await searchParams) ?? {};
  const pageIndex = Math.max(0, Number(pageParam ?? "0") || 0);

  const group = await fetchGroupOrNull(slug);
  if (!group) notFound();

  const reviews = await fetchReviewsOrEmpty(group.publicId, pageIndex);

  return (
    <main className="flex flex-col gap-6">
      <header className="flex flex-col gap-2">
        <Link
          href={`/groups/${encodeURIComponent(group.slug)}`}
          className="text-xs text-fg-muted hover:underline w-fit"
        >
          Back to {group.name}
        </Link>
        <div className="flex flex-wrap items-center gap-3">
          <h1 className="text-2xl font-bold tracking-tight font-display">
            {group.name} - reviews
          </h1>
          <GroupRatingBadge rating={group.rating ?? null} />
        </div>
      </header>

      {reviews.content.length === 0 ? (
        <p className="text-sm text-fg-muted">This group has no reviews yet.</p>
      ) : (
        <ul className="divide-y divide-border">
          {reviews.content.map((r) => (
            <li
              key={`${r.reviewerPublicId}-${r.auctionPublicId}-${r.createdAt}`}
              className="py-4 flex flex-col gap-1"
            >
              <div className="flex items-center justify-between gap-3">
                <Link
                  href={`/users/${encodeURIComponent(r.reviewerPublicId)}`}
                  className="text-sm font-medium hover:underline"
                >
                  {r.reviewerDisplayName}
                </Link>
                <time
                  className="text-xs text-fg-muted"
                  dateTime={r.createdAt}
                  title={r.createdAt}
                >
                  {formatRelativeTime(r.createdAt)}
                </time>
              </div>
              <div className="text-sm" aria-label={`${r.rating} out of 5 stars`}>
                {renderStars(r.rating)}{" "}
                <span className="text-fg-muted">{r.rating}/5</span>
              </div>
              {r.comment && (
                <p className="text-sm whitespace-pre-line">{r.comment}</p>
              )}
              <Link
                href={`/auction/${encodeURIComponent(r.auctionPublicId)}`}
                className="text-xs text-fg-muted hover:underline w-fit"
              >
                {r.auctionTitle}
              </Link>
            </li>
          ))}
        </ul>
      )}

      {reviews.totalPages > 1 && (
        <nav
          className="flex items-center justify-between text-sm"
          aria-label="Pagination"
        >
          {pageIndex > 0 ? (
            <Link
              href={`/groups/${encodeURIComponent(group.slug)}/reviews?page=${pageIndex - 1}`}
              className="hover:underline"
              rel="prev"
            >
              Previous
            </Link>
          ) : (
            <span className="text-fg-muted">Previous</span>
          )}
          <span className="text-fg-muted">
            Page {pageIndex + 1} of {reviews.totalPages}
          </span>
          {pageIndex + 1 < reviews.totalPages ? (
            <Link
              href={`/groups/${encodeURIComponent(group.slug)}/reviews?page=${pageIndex + 1}`}
              className="hover:underline"
              rel="next"
            >
              Next
            </Link>
          ) : (
            <span className="text-fg-muted">Next</span>
          )}
        </nav>
      )}
    </main>
  );
}

async function fetchGroupOrNull(slug: string) {
  try {
    return await realtyGroupsApi.getGroupBySlug(slug);
  } catch (err) {
    if (isApiError(err) && (err.status === 404 || err.status === 410)) {
      return null;
    }
    throw err;
  }
}

async function fetchReviewsOrEmpty(publicId: string, page: number) {
  try {
    return await fetchGroupReviews(publicId, page);
  } catch (err) {
    if (isApiError(err) && err.status === 404) {
      return {
        content: [],
        totalElements: 0,
        totalPages: 0,
        number: page,
        size: 20,
      };
    }
    throw err;
  }
}

function renderStars(rating: number): string {
  const clamped = Math.max(0, Math.min(5, Math.round(rating)));
  return "*".repeat(clamped) + ".".repeat(5 - clamped);
}
```

(Star glyphs in this new file are plain ASCII per the no-emoji rule. The old `/realty/groups/[publicId]/reviews/page.tsx` used Unicode star glyphs; that file is deleted in Part 4 Task 30 so the older artifact doesn't need a separate fix.)

- [ ] **Step 4: Run to confirm it passes**

- [ ] **Step 5: Commit**

```powershell
git add frontend/src/app/groups/`[slug`]/reviews
git commit -m "feat(groups): /groups/[slug]/reviews server page keyed on slug"
```

---

## Push Part 3 commits

```powershell
git push
```

`/groups/[slug]/*` route tree complete; Part 4 continues with `/groups/me`, `/groups/new`, `/groups/invitations/me`, `/admin/groups/*`, nav wiring, internal-reference sweep, old-route deletion, and the final PR.
