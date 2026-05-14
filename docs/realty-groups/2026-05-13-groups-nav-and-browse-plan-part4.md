# Groups Namespace Migration — Part 4: Other routes + nav + sweeps + PR

> Index: [`2026-05-13-groups-nav-and-browse-plan.md`](2026-05-13-groups-nav-and-browse-plan.md). Spec: [`2026-05-13-groups-nav-and-browse-design.md`](2026-05-13-groups-nav-and-browse-design.md).

**Tasks 23–34.** Personal routes + admin migration + nav surface wiring + internal-reference sweep + old-route deletion + SL IM body + Postman + final PR.

**Order rule:** Tasks 23-28 are parallel-safe (different file sets). Task 29 (sweep) lands after all migrations to catch any straggler. Task 30 (delete) lands after Task 29 so deletions don't take out files Task 29 still needs to update. Tasks 31-32 (SL IM + Postman) parallel-safe. Task 33 (audit) after 29-30. Task 34 (PR) terminus.

---

## Task 23: Personal `/groups/me`, `/groups/new`, `/groups/invitations/me` pages [parallel-safe]

**Files:**
- Create: `frontend/src/app/groups/me/page.tsx`
- Create: `frontend/src/app/groups/me/page.test.tsx`
- Create: `frontend/src/app/groups/new/page.tsx`
- Create: `frontend/src/app/groups/new/page.test.tsx`
- Create: `frontend/src/app/groups/invitations/me/page.tsx`
- Create: `frontend/src/app/groups/invitations/me/page.test.tsx`

- [ ] **Step 1: Write a failing render test for each new page**

`frontend/src/app/groups/me/page.test.tsx`:

```tsx
import { describe, expect, it } from "vitest";
import { renderWithProviders, screen } from "@/test/render";
import MyGroupsPage from "./page";

describe("/groups/me", () => {
  it("renders the my-groups list shell", () => {
    renderWithProviders(<MyGroupsPage />);
    expect(screen.getByTestId("my-groups-page")).toBeInTheDocument();
  });
});
```

Mirror for `/groups/new` (testid `group-create-form`) and `/groups/invitations/me` (testid `invitations-recipient-page`).

- [ ] **Step 2: Run the tests to confirm they fail**

```bash
cd frontend && npm test -- --run app/groups/me/page app/groups/new/page app/groups/invitations/me/page
```

Expected: 3 FAILs with `Cannot find module './page'`.

- [ ] **Step 3: Create `/groups/me/page.tsx`**

```tsx
"use client";

import { useMyRealtyGroups } from "@/hooks/realty/useRealtyGroups";
import { useCurrentUser } from "@/lib/user";
import { LoadingSpinner } from "@/components/ui/LoadingSpinner";
import { MyGroupsList } from "@/components/realty/MyGroupsList";

/**
 * Authenticated personal view: every realty group the caller belongs to.
 * Replaces /dashboard/groups. Per spec section 3.2.
 */
export default function MyGroupsPage() {
  const { data: user, isPending: meLoading } = useCurrentUser();
  const myGroups = useMyRealtyGroups();

  if (meLoading || myGroups.isPending) {
    return <LoadingSpinner label="Loading your groups..." />;
  }

  return (
    <div className="mx-auto max-w-4xl px-4 py-8" data-testid="my-groups-page">
      <h1 className="text-xl font-bold tracking-tight font-display mb-6">
        My groups
      </h1>
      <MyGroupsList
        groups={myGroups.data ?? []}
        callerUserPublicId={user?.publicId ?? null}
      />
    </div>
  );
}
```

If `MyGroupsList` does not already exist as a component (the prior `/dashboard/groups/page.tsx` had the list rendering inline), extract its body in-place: copy the JSX from the old page into a new `frontend/src/components/realty/MyGroupsList.tsx` with the same prop shape; both the new `/groups/me` page and the (later-deleted) old page can reuse it temporarily.

- [ ] **Step 4: Create `/groups/new/page.tsx`**

```tsx
"use client";

import { GroupCreateForm } from "@/components/realty/GroupCreateForm";

/**
 * Authenticated create-group form. Replaces /dashboard/groups/create. Per spec
 * section 3.2. The form's post-create redirect updates separately in
 * Task 29 (sweep) — when this page lands, the form still pushes to the old
 * /dashboard/groups/[slug]/manage; Task 29 retargets to /groups/[slug]/profile.
 */
export default function CreateGroupPage() {
  return (
    <div className="mx-auto max-w-2xl px-4 py-8" data-testid="group-create-form">
      <h1 className="text-xl font-bold tracking-tight font-display mb-6">
        Create a realty group
      </h1>
      <GroupCreateForm />
    </div>
  );
}
```

- [ ] **Step 5: Create `/groups/invitations/me/page.tsx`**

```tsx
"use client";

import { useMyGroupInvitations } from "@/hooks/realty/useMyGroupInvitations";
import { LoadingSpinner } from "@/components/ui/LoadingSpinner";
import { MyInvitationsList } from "@/components/realty/MyInvitationsList";

/**
 * Authenticated recipient view: invitations sent TO the caller. Replaces
 * /dashboard/invitations. Per spec section 3.2 + section 5.8 (the bell-icon
 * notification link target).
 */
export default function MyInvitationsPage() {
  const { data, isPending } = useMyGroupInvitations();

  if (isPending) {
    return <LoadingSpinner label="Loading invitations..." />;
  }

  return (
    <div className="mx-auto max-w-3xl px-4 py-8" data-testid="invitations-recipient-page">
      <h1 className="text-xl font-bold tracking-tight font-display mb-6">
        Group invitations
      </h1>
      <MyInvitationsList invitations={data ?? []} />
    </div>
  );
}
```

If `useMyGroupInvitations` and `MyInvitationsList` do not yet exist as separate units (the prior `/dashboard/invitations` page likely rendered both inline), extract them to their own files alongside this migration:
- `frontend/src/hooks/realty/useMyGroupInvitations.ts` — TanStack Query hook calling the existing recipient-invitations endpoint. Query key `["realty", "my-invitations"]`. `staleTime: 60_000` (matches the existing notification-count poll cadence per spec section 5.2).
- `frontend/src/components/realty/MyInvitationsList.tsx` — the existing list JSX extracted from the old `/dashboard/invitations` page.

- [ ] **Step 6: Run the tests to confirm they pass**

```bash
npm test -- --run app/groups/me/page app/groups/new/page app/groups/invitations/me/page
```

Expected: 3 PASSes.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/app/groups/me/ frontend/src/app/groups/new/ frontend/src/app/groups/invitations/me/ \
        frontend/src/components/realty/MyGroupsList.tsx \
        frontend/src/components/realty/MyInvitationsList.tsx \
        frontend/src/hooks/realty/useMyGroupInvitations.ts
git commit -m "feat(groups): /groups/me, /groups/new, /groups/invitations/me personal routes"
```

---

## Task 24: `/admin/groups/*` route tree [parallel-safe]

**Files:**
- Create: `frontend/src/app/admin/groups/page.tsx`
- Create: `frontend/src/app/admin/groups/[slug]/page.tsx`
- Create: `frontend/src/app/admin/groups/reports/page.tsx`
- Create: `frontend/src/app/admin/groups/reports/[publicId]/page.tsx`
- Modify: `frontend/src/components/admin/realty-groups/AdminRealtyGroupDetailPage.tsx` (slug→publicId resolution at the top)
- Test files for each new page.

- [ ] **Step 1: Write failing render tests**

```tsx
// frontend/src/app/admin/groups/page.test.tsx
import { describe, expect, it } from "vitest";
import { renderWithProviders, screen } from "@/test/render";
import AdminGroupsListPage from "./page";

describe("/admin/groups", () => {
  it("renders the admin groups list", () => {
    renderWithProviders(<AdminGroupsListPage />);
    expect(screen.getByTestId("admin-realty-groups-list-page")).toBeInTheDocument();
  });
});
```

Mirror tests for `/admin/groups/[slug]`, `/admin/groups/reports`, `/admin/groups/reports/[publicId]`.

- [ ] **Step 2: Run the tests to confirm they fail**

```bash
npm test -- --run app/admin/groups
```

Expected: 4 FAILs.

- [ ] **Step 3: Create `/admin/groups/page.tsx`**

```tsx
import { AdminRealtyGroupsListPage } from "@/components/admin/realty-groups/AdminRealtyGroupsListPage";

export default function AdminGroupsListRoute() {
  return <AdminRealtyGroupsListPage />;
}
```

If the existing list component's row click targets `/admin/realty-groups/[publicId]`, those targets get updated in Task 29 (sweep) to point at `/admin/groups/[slug]`. The component itself stays in place under `frontend/src/components/admin/realty-groups/`.

- [ ] **Step 4: Create `/admin/groups/[slug]/page.tsx`**

```tsx
"use client";

import { useParams } from "next/navigation";
import { LoadingSpinner } from "@/components/ui/LoadingSpinner";
import { useRealtyGroupBySlug } from "@/hooks/realty/useRealtyGroups";
import { AdminRealtyGroupDetailPage } from "@/components/admin/realty-groups/AdminRealtyGroupDetailPage";

export default function AdminGroupDetailRoute() {
  const params = useParams<{ slug: string }>();
  const slug = params?.slug;
  const group = useRealtyGroupBySlug(slug);

  if (group.isPending) return <LoadingSpinner label="Loading group..." />;
  if (group.isError || !group.data) {
    return (
      <div className="mx-auto max-w-3xl px-4 py-8">
        <p className="text-sm text-danger">Could not load this group.</p>
      </div>
    );
  }

  return <AdminRealtyGroupDetailPage publicId={group.data.publicId} />;
}
```

The existing detail component already accepts `publicId` as a prop. The wrapper resolves slug→publicId so the component is unchanged. If the existing component reads publicId from `useParams` directly, refactor it to accept `publicId` as a prop in this same task.

- [ ] **Step 5: Create `/admin/groups/reports/page.tsx` and `/admin/groups/reports/[publicId]/page.tsx`**

Reports remain publicId-keyed because the publicId here belongs to the report, not the group. Mirror the existing `/admin/realty-groups/reports/*` page wrappers — copy + delete the originals in Task 30.

```tsx
// frontend/src/app/admin/groups/reports/page.tsx
import { AdminGroupReportsQueuePage } from "@/components/admin/realty-groups/AdminGroupReportsQueuePage";
export default function AdminGroupReportsQueueRoute() {
  return <AdminGroupReportsQueuePage />;
}
```

```tsx
// frontend/src/app/admin/groups/reports/[publicId]/page.tsx
import { AdminGroupReportDetailPage } from "@/components/admin/realty-groups/AdminGroupReportDetailPage";
export default function AdminGroupReportDetailRoute() {
  return <AdminGroupReportDetailPage />;
}
```

- [ ] **Step 6: Run the tests to confirm they pass**

```bash
npm test -- --run app/admin/groups
```

Expected: 4 PASSes.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/app/admin/groups/ \
        frontend/src/components/admin/realty-groups/AdminRealtyGroupDetailPage.tsx
git commit -m "feat(groups): /admin/groups/* admin route tree (slug-keyed detail)"
```

---

## Task 25: Header "Groups" link [parallel-safe]

**Files:**
- Modify: `frontend/src/components/layout/Header.tsx`
- Modify: `frontend/src/components/layout/Header.test.tsx`

- [ ] **Step 1: Write a failing test**

```tsx
it("renders a Groups nav link in the header", () => {
  renderWithProviders(<Header />);
  const link = screen.getByRole("link", { name: /^Groups$/ });
  expect(link).toHaveAttribute("href", "/groups");
});
```

- [ ] **Step 2: Run the test to confirm it fails**

```bash
npm test -- --run components/layout/Header.test
```

Expected: FAIL with "Unable to find role=link with name /^Groups$/".

- [ ] **Step 3: Add the nav link**

Find the existing Browse / Sell parcel nav block in `Header.tsx` and insert between them:

```tsx
<NavLink variant="header" href="/groups">Groups</NavLink>
```

- [ ] **Step 4: Run the test to confirm it passes**

```bash
npm test -- --run components/layout/Header.test
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/components/layout/Header.tsx \
        frontend/src/components/layout/Header.test.tsx
git commit -m "feat(groups): top-level Groups nav link in Header"
```

---

## Task 26: UserMenuDropdown items + invitations badge [parallel-safe]

**Files:**
- Modify: `frontend/src/components/auth/UserMenuDropdown.tsx`
- Modify: `frontend/src/components/auth/UserMenuDropdown.test.tsx`

- [ ] **Step 1: Write failing tests**

```tsx
it("includes 'My groups' and 'Invitations' items when authenticated", async () => {
  // user fixture: authenticated, with one pending invitation
  server.use(
    http.get("*/api/v1/me/group-invitations", () =>
      HttpResponse.json([{ publicId: "i1" }]))
  );
  renderWithProviders(<UserMenuDropdown user={mockUser} />);
  await userEvent.click(screen.getByRole("button", { name: /user menu/i }));

  expect(screen.getByRole("menuitem", { name: "My groups" })).toBeInTheDocument();
  const invites = screen.getByRole("menuitem", { name: /Invitations/ });
  expect(invites).toBeInTheDocument();
  expect(within(invites).getByTestId("invitations-badge")).toHaveTextContent("1");
});
```

- [ ] **Step 2: Run the test to confirm it fails**

```bash
npm test -- --run components/auth/UserMenuDropdown.test
```

Expected: FAIL.

- [ ] **Step 3: Update `UserMenuDropdown.tsx`**

Wire `useMyGroupInvitations` (or a slim `useMyGroupInvitationsCount` wrapper) into the component. Items array gains:

```tsx
const invitations = useMyGroupInvitations();
const inviteCount = invitations.data?.length ?? 0;

const items = [
  { label: "Dashboard", onSelect: () => router.push("/dashboard") },
  { label: "Profile", onSelect: () => router.push(`/users/${user.publicId}`) },
  {
    label: "My groups",
    onSelect: () => router.push("/groups/me"),
  },
  {
    label: "Invitations",
    onSelect: () => router.push("/groups/invitations/me"),
    badge: inviteCount > 0 ? inviteCount : undefined,
  },
  { label: "Settings", onSelect: () => router.push("/settings/profile") },
  ...(user.role === "ADMIN"
    ? [{ label: "Admin", onSelect: () => router.push("/admin") }]
    : []),
  { label: "Sign Out", onSelect: () => logout.mutate(), danger: true },
];
```

The `Dropdown` item shape may need a `badge?: number` field — extend the component's prop type. The badge renders next to the label as a small chip with `data-testid="invitations-badge"`.

- [ ] **Step 4: Run the test to confirm it passes**

```bash
npm test -- --run components/auth/UserMenuDropdown.test
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/components/auth/UserMenuDropdown.tsx \
        frontend/src/components/auth/UserMenuDropdown.test.tsx \
        frontend/src/components/ui/Dropdown.tsx
git commit -m "feat(groups): UserMenuDropdown -- My groups + Invitations (with pending badge)"
```

---

## Task 27: MobileMenu items [parallel-safe]

**Files:**
- Modify: `frontend/src/components/layout/MobileMenu.tsx`
- Modify: `frontend/src/components/layout/MobileMenu.test.tsx`

- [ ] **Step 1: Write failing tests for top-level Groups + auth-only My groups + Invitations**

```tsx
it("renders the top-level Groups link in mobile menu", () => {
  renderWithProviders(<MobileMenu open onClose={() => {}} />);
  expect(screen.getByRole("link", { name: /^Groups$/ })).toHaveAttribute("href", "/groups");
});

it("renders My groups + Invitations in the authenticated block", () => {
  // mock useAuth to return authenticated
  renderWithProviders(<MobileMenu open onClose={() => {}} />);
  expect(screen.getByRole("link", { name: "My groups" })).toHaveAttribute("href", "/groups/me");
  expect(screen.getByRole("link", { name: /Invitations/ })).toHaveAttribute(
    "href",
    "/groups/invitations/me",
  );
});
```

- [ ] **Step 2: Run the tests to confirm they fail**

```bash
npm test -- --run components/layout/MobileMenu.test
```

Expected: FAILs.

- [ ] **Step 3: Update `MobileMenu.tsx`**

Add a top-level "Groups" link in the public nav block. In the authenticated block, add "My groups" → `/groups/me` and "Invitations" → `/groups/invitations/me`. The invitations badge mirrors the UserMenuDropdown pattern (`useMyGroupInvitations` data length).

- [ ] **Step 4: Run the tests to confirm they pass**

```bash
npm test -- --run components/layout/MobileMenu.test
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/components/layout/MobileMenu.tsx \
        frontend/src/components/layout/MobileMenu.test.tsx
git commit -m "feat(groups): MobileMenu -- top-level Groups + My groups + Invitations"
```

---

## Task 28: Dashboard overview "Your groups" + admin dashboard "Groups" card [parallel-safe]

**Files:**
- Create: `frontend/src/components/dashboard/YourGroupsSection.tsx`
- Create: `frontend/src/components/dashboard/YourGroupsSection.test.tsx`
- Modify: `frontend/src/app/dashboard/(verified)/(onboarded)/overview/page.tsx`
- Modify: `frontend/src/components/admin/dashboard/AdminDashboardPage.tsx`
- Modify: `frontend/src/components/admin/dashboard/AdminDashboardPage.test.tsx`

- [ ] **Step 1: Write failing `YourGroupsSection` test**

```tsx
it("renders a card per group with role chip + quick action links", () => {
  const groups = [
    { publicId: "g1", slug: "sunset", name: "Sunset", role: "LEADER" },
    { publicId: "g2", slug: "moonlit", name: "Moonlit", role: "AGENT" },
  ];
  renderWithProviders(<YourGroupsSection groups={groups} />);

  expect(screen.getAllByTestId("your-groups-card")).toHaveLength(2);
  expect(screen.getByRole("link", { name: /Profile.*Sunset/i }))
    .toHaveAttribute("href", "/groups/sunset/profile");
  expect(screen.getByRole("link", { name: /Wallet.*Sunset/i }))
    .toHaveAttribute("href", "/groups/sunset/wallet");
});

it("renders nothing when groups list is empty", () => {
  const { container } = renderWithProviders(<YourGroupsSection groups={[]} />);
  expect(container.firstChild).toBeNull();
});
```

- [ ] **Step 2: Run the test to confirm it fails**

```bash
npm test -- --run components/dashboard/YourGroupsSection.test
```

Expected: FAIL with "Cannot find module".

- [ ] **Step 3: Create `YourGroupsSection.tsx`**

```tsx
"use client";

import Link from "next/link";

type YourGroup = {
  publicId: string;
  slug: string;
  name: string;
  role: "LEADER" | "AGENT";
};

export interface YourGroupsSectionProps {
  groups: YourGroup[];
}

export function YourGroupsSection({ groups }: YourGroupsSectionProps) {
  if (groups.length === 0) return null;
  return (
    <section className="flex flex-col gap-3" data-testid="your-groups-section">
      <h2 className="text-sm font-semibold text-fg">Your groups</h2>
      <ul className="flex flex-col gap-2">
        {groups.map((g) => (
          <li
            key={g.publicId}
            className="rounded-lg border border-border bg-surface-raised p-3 flex items-center justify-between gap-3"
            data-testid="your-groups-card"
          >
            <div className="flex flex-col">
              <Link
                href={`/groups/${g.slug}`}
                className="text-sm font-semibold text-fg hover:underline"
              >
                {g.name}
              </Link>
              <span className="text-xs text-fg-muted uppercase tracking-wide">
                {g.role === "LEADER" ? "Leader" : "Agent"}
              </span>
            </div>
            <div className="flex gap-1 flex-wrap text-xs">
              <Link
                href={`/groups/${g.slug}/profile`}
                aria-label={`Profile for ${g.name}`}
                className="rounded bg-bg-subtle px-2 py-1 text-fg hover:bg-bg-muted"
              >
                Profile
              </Link>
              <Link
                href={`/groups/${g.slug}/wallet`}
                aria-label={`Wallet for ${g.name}`}
                className="rounded bg-bg-subtle px-2 py-1 text-fg hover:bg-bg-muted"
              >
                Wallet
              </Link>
              <Link
                href={`/groups/${g.slug}/members`}
                aria-label={`Members for ${g.name}`}
                className="rounded bg-bg-subtle px-2 py-1 text-fg hover:bg-bg-muted"
              >
                Members
              </Link>
              <Link
                href={`/groups/${g.slug}/reviews`}
                aria-label={`Reviews for ${g.name}`}
                className="rounded bg-bg-subtle px-2 py-1 text-fg hover:bg-bg-muted"
              >
                Reviews
              </Link>
            </div>
          </li>
        ))}
      </ul>
    </section>
  );
}
```

- [ ] **Step 4: Wire `YourGroupsSection` into the overview page**

Edit `frontend/src/app/dashboard/(verified)/(onboarded)/overview/page.tsx`:

```tsx
"use client";

import { CancellationHistorySection } from "@/components/dashboard/CancellationHistorySection";
import { SuspensionBanner } from "@/components/dashboard/SuspensionBanner";
import { YourGroupsSection } from "@/components/dashboard/YourGroupsSection";
import { PendingReviewsSection } from "@/components/reviews/PendingReviewsSection";
import { VerifiedOverview } from "@/components/user/VerifiedOverview";
import { useMyRealtyGroups } from "@/hooks/realty/useRealtyGroups";
import { useCurrentUser } from "@/lib/user";

export default function OverviewPage() {
  const me = useCurrentUser();
  const myGroups = useMyRealtyGroups();

  const groupsForSection = (myGroups.data ?? []).map((g) => ({
    publicId: g.publicId,
    slug: g.slug,
    name: g.name,
    role: g.leader.userPublicId === me.data?.publicId ? "LEADER" : "AGENT" as const,
  }));

  return (
    <div className="flex flex-col gap-8">
      <SuspensionBanner />
      <PendingReviewsSection />
      <VerifiedOverview />
      <YourGroupsSection groups={groupsForSection} />
      <CancellationHistorySection />
    </div>
  );
}
```

- [ ] **Step 5: Add the admin dashboard Groups card**

Edit `frontend/src/components/admin/dashboard/AdminDashboardPage.tsx` — find the existing fraud-flags / reports / disputes cards and add:

```tsx
import { UsersRound } from "lucide-react";

<DashboardCard
  href="/admin/groups"
  icon={<UsersRound />}
  label="Groups"
  data-testid="admin-dashboard-groups-card"
/>
```

(Use the project's existing icon import path from `@/components/ui/icons` if `UsersRound` is re-exported there; otherwise import directly from `lucide-react`.)

- [ ] **Step 6: Write a failing test for the admin dashboard card**

```tsx
it("renders a Groups admin card linking to /admin/groups", () => {
  renderWithProviders(<AdminDashboardPage />);
  expect(screen.getByTestId("admin-dashboard-groups-card"))
    .toHaveAttribute("href", "/admin/groups");
});
```

- [ ] **Step 7: Run all tests to confirm they pass**

```bash
npm test -- --run components/dashboard/YourGroupsSection.test admin/dashboard/AdminDashboardPage.test
```

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add frontend/src/components/dashboard/YourGroupsSection.tsx \
        frontend/src/components/dashboard/YourGroupsSection.test.tsx \
        "frontend/src/app/dashboard/(verified)/(onboarded)/overview/page.tsx" \
        frontend/src/components/admin/dashboard/AdminDashboardPage.tsx \
        frontend/src/components/admin/dashboard/AdminDashboardPage.test.tsx
git commit -m "feat(groups): dashboard overview Your groups section + admin Groups card"
```

---

## Task 29: Internal reference sweep

**Files:**
- Modify: every component with a hardcoded old-path link target. Use the grep below to enumerate.

- [ ] **Step 1: Enumerate sites**

```bash
git grep -E '"/dashboard/groups|"/realty/groups|"/group/|"/admin/realty-groups' frontend/src \
  | grep -v '\.test\.' | grep -v 'page\.tsx' \
  > .scratch/sweep-targets.txt
cat .scratch/sweep-targets.txt
```

Expected sites (per spec section 7.4 + code audit):

- `frontend/src/components/realty/EditGroupAffordance.tsx` — `/dashboard/groups/[slug]/manage` → `/groups/[slug]/profile`
- `frontend/src/components/realty/GroupCreateForm.tsx` — post-create redirect → `/groups/[slug]/profile`
- `frontend/src/components/realty/SettingsTab.tsx` — both `router.push("/dashboard/groups")` calls → `/groups/me`
- `frontend/src/components/realty/GroupRatingBadge.tsx` — `/realty/groups/[publicId]/reviews` → `/groups/[slug]/reviews` (prop changes from publicId to slug)
- `frontend/src/components/auction/GroupAttributionLine.tsx` — `/group/[slug]` → `/groups/[slug]`
- `frontend/src/components/realty/GroupBadge.tsx` — `/group/[slug]` → `/groups/[slug]`
- `frontend/src/components/realty/GroupChip.tsx` — `/group/[slug]` → `/groups/[slug]`
- `frontend/src/components/realty/LeaderCard.tsx` — verify; if it links anywhere `/group/[slug]`, update
- Any MSW fixture URL references in `frontend/src/test/msw/handlers.ts` or test files
- `frontend/src/components/admin/realty-groups/AdminRealtyGroupsListPage.tsx` — row click target → `/admin/groups/[slug]`
- `frontend/src/components/admin/realty-groups/AdminGroupReportsQueuePage.tsx` (if it has a link to a group's admin detail page) — `/admin/realty-groups/[publicId]` → `/admin/groups/[slug]`
- `frontend/src/lib/admin/action-type-labels.ts` (verify it has no URL strings — most likely doesn't)

- [ ] **Step 2: Update each site with sed-style precision (Edit tool, one Replace per site)**

For each file in the sweep list, swap the URL literal:

```tsx
// Before:
href={`/dashboard/groups/${slug}/manage`}
// After:
href={`/groups/${slug}/profile`}
```

`GroupRatingBadge` is more involved — its prop signature changes:

```tsx
// Before:
export interface GroupRatingBadgeProps {
  groupPublicId: string;
  rating: GroupRating;
}
// inside:
href={`/realty/groups/${encodeURIComponent(groupPublicId)}/reviews`}

// After:
export interface GroupRatingBadgeProps {
  groupSlug: string;
  rating: GroupRating;
}
// inside:
href={`/groups/${encodeURIComponent(groupSlug)}/reviews`}
```

Every consumer of `GroupRatingBadge` updates its prop name + value. Grep for callers:

```bash
git grep "GroupRatingBadge" frontend/src
```

- [ ] **Step 3: Run all tests to confirm the sweep didn't break anything**

```bash
npm test -- --run
```

Expected: green except for tests that asserted on old paths — those need to be updated in lockstep.

- [ ] **Step 4: Confirm grep is clean (with intentional exceptions for tests + docs)**

```bash
git grep -E '"/dashboard/groups|"/realty/groups|"/group/' frontend/src \
  | grep -v '\.test\.' | grep -v 'docs/'
```

Expected output: empty.

```bash
git grep '"/admin/realty-groups' frontend/src | grep -v '\.test\.'
```

Expected output: empty.

- [ ] **Step 5: Commit**

```bash
git add -A frontend/src
git commit -m "refactor(groups): sweep every hardcoded link target to /groups/* paths"
```

---

## Task 30: Delete old route directories

**Files (all deletions):**
- `frontend/src/app/dashboard/(verified)/groups/` (entire subtree)
- `frontend/src/app/dashboard/(verified)/invitations/`
- `frontend/src/app/realty/groups/` (entire subtree)
- `frontend/src/app/group/`
- `frontend/src/app/admin/realty-groups/` (entire subtree)

- [ ] **Step 1: Delete each directory**

```bash
git rm -r "frontend/src/app/dashboard/(verified)/groups"
git rm -r "frontend/src/app/dashboard/(verified)/invitations"
git rm -r frontend/src/app/realty/groups
# If frontend/src/app/realty/ is otherwise empty after removing groups, delete it too:
rmdir frontend/src/app/realty 2>/dev/null || true
git rm -r frontend/src/app/group
git rm -r frontend/src/app/admin/realty-groups
```

- [ ] **Step 2: Confirm the build still passes**

```bash
npm run build
```

Expected: `Compiled successfully`.

- [ ] **Step 3: Confirm no orphaned references**

```bash
git grep -E 'app/dashboard/\(verified\)/groups|app/realty/groups|app/group/|app/admin/realty-groups' frontend/src \
  | grep -v '\.test\.'
```

Expected output: empty.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "refactor(groups): delete old route directories (clean break per spec section 3.6)"
```

---

## Task 31: SL IM dispatcher template update for invitation notification [parallel-safe]

**Files:**
- Modify: wherever the invitation-recipient SL IM body template lives (grep to find)

- [ ] **Step 1: Locate the invitation SL IM body template**

```bash
grep -rn "GROUP_INVITATION\|invitation.*IM\|invitation.*body\|invitation.*message" \
  backend/src/main/java/com/slparcelauctions/backend/notification/slim/ | head -20
```

Expected: a `case GROUP_INVITATION_RECEIVED:` branch in the template builder, or a per-category template file under `backend/src/main/resources/templates/sl-im/`.

- [ ] **Step 2: Write a failing test asserting the URL is in the body**

In the template-builder test (or the relevant `@SpringBootTest`):

```java
@Test
void invitationImBodyIncludesRecipientPageUrl() {
    var notification = Notification.builder()
        .category(NotificationCategory.GROUP_INVITATION_RECEIVED)
        .body("You have a new realty group invitation.")
        // ... other fields
        .build();

    String imBody = templateBuilder.buildSlImBody(notification);

    assertThat(imBody).contains("/groups/invitations/me");
}
```

- [ ] **Step 3: Run the test to confirm it fails**

```bash
./mvnw test -Dtest=SlImTemplateBuilderTest
```

Expected: FAIL — the body does not yet contain the URL.

- [ ] **Step 4: Append the URL to the template**

Edit the template builder for `GROUP_INVITATION_RECEIVED`:

```java
case GROUP_INVITATION_RECEIVED -> notification.getBody() +
    "\nView at " + appBaseUrl() + "/groups/invitations/me";
```

Where `appBaseUrl()` resolves to the env-appropriate base URL (e.g. `https://slpa.app` in prod) via the existing `slpa.app.base-url` config or equivalent that the template builder already uses for other notification categories.

- [ ] **Step 5: Run the test to confirm it passes**

```bash
./mvnw test -Dtest=SlImTemplateBuilderTest
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/notification/slim/ \
        backend/src/test/java/com/slparcelauctions/backend/notification/slim/
git commit -m "feat(groups): SL IM body for invitation appends /groups/invitations/me URL"
```

---

## Task 32: Postman docs update [parallel-safe]

**Files:**
- Modify: `docs/postman/realty-groups-g-additions.md`

- [ ] **Step 1: Edit the doc to add the new browse request**

Append a new section after the existing folder additions:

```markdown
## 5. Realty Groups → Browse (new in Groups namespace migration)

### List public groups

- **Method / URL**: `GET {{baseUrl}}/api/v1/realty-groups?q=&page=0&size=20&sort=RATING`
- **Auth**: none (anonymous-accessible)
- **Query params**:
  - `q` — optional search; case-insensitive substring on name + description.
  - `page` — default 0.
  - `size` — default 20, clamped to 60.
  - `sort` — one of `RATING|NEWEST|MOST_ACTIVE_LISTINGS|MOST_SALES`. Default `RATING`.
- Returns `PagedResponse<RealtyGroupCardDto>`. Verified-only + non-suspended filters are implicit.
```

Also update any path references in the existing sections from `/dashboard/groups` or `/realty/groups` or `/group/[slug]` to the new `/groups/*` shape if they're mentioned anywhere.

- [ ] **Step 2: Commit**

```bash
git add docs/postman/realty-groups-g-additions.md
git commit -m "docs(groups): Postman Browse Groups request"
```

If the cloud Postman collection is also updated via MCP (`mcp__postman__createCollectionRequest`), do it in the same task — but the markdown doc is the source-of-truth deliverable for the migration.

---

## Task 33: `next build` + full backend/frontend test pass + grep audit

**Files:** none modified; verification only.

- [ ] **Step 1: Backend tests**

```bash
cd backend && ./mvnw test
```

Expected: green (or only the documented pre-existing flake, which Sub-project G already de-flaked).

- [ ] **Step 2: Frontend lint + test + build**

```bash
cd frontend
npm run lint
npm test -- --run
npm run build
```

Expected: lint clean (or only documented pre-existing warnings); 100% test pass; build success.

- [ ] **Step 3: Final grep audit**

```bash
git grep -E '"/dashboard/groups|"/realty/groups|"/group/\[|"/admin/realty-groups' frontend/src \
  | grep -v '\.test\.' | grep -v 'docs/'
```

Expected: empty.

```bash
git grep -E 'app/dashboard/\(verified\)/groups|app/realty/groups|app/group/|app/admin/realty-groups' frontend/src
```

Expected: empty.

```bash
find frontend/src/app -type d -name "realty" -o -name "group" 2>/dev/null
```

Expected: empty.

```bash
git status
```

Expected: clean working tree.

If any check fails, return to the relevant task to fix the residue before opening the PR.

---

## Task 34: Final PR into `dev`

**Files:** none modified.

- [ ] **Step 1: Push the branch**

```bash
git push
```

- [ ] **Step 2: Open the PR**

```bash
gh pr create --base dev --head feat/groups-namespace-migration \
  --title "feat: /groups namespace migration + public Browse Groups directory + nav surfaces" \
  --body "$(cat <<'EOF'
Consolidates every realty-group surface under /groups/* (admin under /admin/groups/*) and ships a public Browse Groups directory at /groups with a top-level nav entry.

## What ships

- New public directory page at /groups (search + sort + card grid; verified groups only).
- New backend endpoint GET /api/v1/realty-groups with RealtyGroupCardDto.
- Every existing /dashboard/groups, /realty/groups, /group, /admin/realty-groups route relocated to its /groups/* (or /admin/groups/*) equivalent.
- Flattened group operational surface: /groups/[slug]/profile, /members, /wallet, /sl-groups, /invitations, /analytics/commissions, /reviews, /settings — each its own URL, persistent sub-nav across them.
- Reserved-slug validator rejects new / me / invitations at create-time.
- Header gains top-level "Groups" link. UserMenuDropdown gains "My groups" + "Invitations" (with pending badge). MobileMenu mirrors. Dashboard overview gains a "Your groups" section. Admin dashboard gains a "Groups" card.
- Invitation notification linkUrl populated for the bell row; SL IM body appends the recipient page URL.
- Clean break on old URLs — no redirects; every old route is deleted in the same PR.

## Manual verification

- Click "Groups" in Header → directory loads → card → public profile → sub-nav item → operational surface.
- Sign in as an invited user → bell icon → invitation row → recipient page.
- Try to create a group with slug "me" — backend returns 422 RESERVED_SLUG.

## Acceptance

Per spec section 10:

- [x] ./mvnw test passes.
- [x] cd frontend && npm run verify passes.
- [x] next build succeeds.
- [x] git grep returns empty for old path patterns.
- [x] Every nav surface has documented links per spec section 5.
- [x] <GroupsPage cardLayout="cover" sidebar="left" /> renders without prop warnings.

## Out of scope (tracked)

- Slug rename / slug_history table.
- HTTP redirects (pre-launch posture).
- Renaming /listings/create to /listings/new.

Spec: docs/realty-groups/2026-05-13-groups-nav-and-browse-design.md
Plan: docs/realty-groups/2026-05-13-groups-nav-and-browse-plan.md
EOF
)"
```

- [ ] **Step 3: Verify PR opened**

The PR URL appears in the gh CLI output. Confirm it targets `dev`, not `main`. Per memory `feedback_no_merge_to_main`, the dev → main merge is gated on explicit user authorization in a separate step.
