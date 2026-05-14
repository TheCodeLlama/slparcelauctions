# Realty Groups: `/groups` namespace + public directory + nav surfaces — Design

**Date:** 2026-05-13
**Spec lives at:** `docs/realty-groups/` per user override of the default `docs/superpowers/specs/` location.

---

## 1. Goal

Consolidate every realty-group surface under a single `/groups/*` URL namespace, ship a public Browse Groups directory page at `/groups`, and wire up every navigation surface (Header, mobile menu, user menu, dashboard overview, admin dashboard, notification bell, SL IM dispatcher) so that no realty-group route requires URL typing to reach.

The user-visible directive: "Groups" becomes a top-level nav item linking to `/groups`. The implicit directive: every operational sub-page is reachable from a click path that starts at one of those top-level entries.

## 2. Scope

**In scope (single PR):**

1. New public directory page at `/groups` with search + sort + card grid (template imported from claude.ai/design as `<GroupsPage cardLayout="cover" sidebar="left" />`).
2. New backend endpoint `GET /api/v1/realty-groups` with `RealtyGroupCardDto`.
3. Migration of every existing group-related route from `/dashboard/groups/*`, `/realty/groups/*`, `/group/*`, `/dashboard/invitations`, `/admin/realty-groups/*` to the new `/groups/*` and `/admin/groups/*` paths.
4. Flattening of the existing tabbed manage page into a layout-with-sub-nav per the route map below.
5. Reserved-slug validator additions: `new`, `me`, `invitations`.
6. Nav additions on every surface listed in §5.
7. Notification link target population for invitation-received rows; SL IM body update.
8. Old route files deleted; internal references swept; Postman docs updated.

**Out of scope:**

- HTTP redirects from old URLs (pre-launch posture; clean break per user directive).
- Slug-rename support / `slug_history` table.
- Renaming `/listings/create` to `/listings/new` for consistency (mentioned in brainstorm; out of scope).
- Any new realty-group features beyond the browse page — sub-projects A-G already shipped the operational surfaces; this spec is structural reorganization + one new public page.

## 3. URL map

### 3.1 Public, anonymous-accessible

| New URL | Purpose | Replaces |
|---|---|---|
| `/groups` | Public directory (search + sort + card grid; verified groups only) | New page |
| `/groups/[slug]` | Public group profile | `/group/[slug]` |
| `/groups/[slug]/reviews` | Reviews list page | `/realty/groups/[publicId]/reviews` |

### 3.2 Authenticated, user-personal

| New URL | Purpose | Replaces |
|---|---|---|
| `/groups/me` | "My groups" memberships list | `/dashboard/groups` |
| `/groups/new` | Create-group form | `/dashboard/groups/create` |
| `/groups/invitations/me` | Recipient view: invitations sent TO the caller | `/dashboard/invitations` |

### 3.3 Authenticated, group-scoped (leader/agent operations)

| New URL | Purpose | Replaces |
|---|---|---|
| `/groups/[slug]/profile` | Editable profile form | Profile tab on `/manage` |
| `/groups/[slug]/members` | Members list + per-member commission edit | Members tab on `/manage` |
| `/groups/[slug]/invitations` | Sender view: invitations FROM this group | Invitations tab on `/manage` |
| `/groups/[slug]/settings` | Settings (leader-only: dissolve, transfer leadership) | Settings tab on `/manage` |
| `/groups/[slug]/wallet` | Wallet balance + ledger + withdraw | `/realty/groups/[publicId]/wallet` |
| `/groups/[slug]/sl-groups` | Registered SL groups | `/realty/groups/[publicId]/sl-groups` |
| `/groups/[slug]/analytics/commissions` | Per-member commission analytics | `/realty/groups/[publicId]/analytics/commissions` |

### 3.4 Admin

| New URL | Purpose | Replaces |
|---|---|---|
| `/admin/groups` | Admin list (all groups incl. suspended) | `/admin/realty-groups` |
| `/admin/groups/[slug]` | Admin detail (uses slug, not publicId) | `/admin/realty-groups/[publicId]` |
| `/admin/groups/reports` | Admin reports queue | `/admin/realty-groups/reports` |
| `/admin/groups/reports/[publicId]` | Admin single-report detail (publicId is the report's, not the group's) | `/admin/realty-groups/reports/[publicId]` |

### 3.5 Reserved slugs

The slug validator rejects (at create-time) every literal child segment of `/groups/*`:

- `new` (collides with `/groups/new`)
- `me` (collides with `/groups/me`)
- `invitations` (collides with `/groups/invitations/me`)

Any existing groups already using those slugs continue to operate; only *new* registrations are rejected.

### 3.6 Old URL handling

**No redirects.** Pre-launch, no real users, no public bookmark surface to protect. Old route files are deleted in the same PR. Internal references (link targets in components, tests, fixtures, docs, notification bodies) are swept in the same PR to match.

## 4. Identifier strategy: slug everywhere

Every group-related URL — public, member-only, admin — keys on the group's slug, not its `publicId`. This unifies the previously-split convention (slug for public + manage, publicId for wallet/sl-groups/reviews/analytics/admin) into a single shape.

**Implication: slugs are stable.** No rename UI; the existing create-time slug choice is the permanent slug. If a rename feature is added in the future, it lands alongside a `slug_history` table that maps old slugs to current and a server-side redirect path for `/groups/[old-slug]/*` → `/groups/[current-slug]/*`. That's a separate spec.

## 5. Nav placements

### 5.1 Header (top nav)

New top-level **"Groups"** link between Browse and Sell parcel:

```tsx
<NavLink variant="header" href="/groups">Groups</NavLink>
```

Visible to anonymous + authenticated.

### 5.2 `UserMenuDropdown` (authenticated only)

Add items between Profile and Settings:

- **"My groups"** → `/groups/me`
- **"Invitations"** → `/groups/invitations/me` — with a small numeric badge when the caller has pending invitations. Badge data source: a new `useMyGroupInvitationsCount()` hook that piggy-backs on the existing notification-count poll (the bell-icon counter already polls; the invitations count rides the same cadence). Backend exposes the count via the existing `/api/v1/me/group-invitations` response (existing length) or, if needed for a slimmer wire shape, a new `GET /api/v1/me/group-invitations/count` endpoint added in the same PR.

### 5.3 `MobileMenu`

Mirror Header + `UserMenuDropdown` changes:

- Top-level "Groups" link.
- Authenticated-only block: "My groups", "Invitations" (with badge).

### 5.4 `/groups/[slug]/*` layout — persistent sub-nav

`frontend/src/app/groups/[slug]/layout.tsx` renders a horizontal sub-nav strip persistent across every member-facing sub-page. Items are conditionally rendered on caller permissions:

| Sub-nav item | Visibility predicate | Route |
|---|---|---|
| Profile | leader or member | `/groups/[slug]/profile` |
| Members | leader or member | `/groups/[slug]/members` |
| Wallet | leader or `VIEW_GROUP_TRANSACTIONS` | `/groups/[slug]/wallet` |
| SL Groups | leader or `REGISTER_SL_GROUP` | `/groups/[slug]/sl-groups` |
| Analytics | leader or `MANAGE_MEMBERS` | `/groups/[slug]/analytics/commissions` |
| Invitations | leader or `INVITE_AGENTS` | `/groups/[slug]/invitations` |
| Reviews | everyone (public) | `/groups/[slug]/reviews` |
| Settings | leader only | `/groups/[slug]/settings` |

Non-members deep-linking to any `/groups/[slug]/*` member-only route (everything except `/reviews`) are redirected to `/groups/[slug]` (the public profile) — same pattern the existing manage page uses today.

### 5.5 Public profile `/groups/[slug]`

`EditGroupAffordance` (existing component, members-only): flip link target from `/dashboard/groups/[slug]/manage` to `/groups/[slug]/profile`.

### 5.6 Dashboard overview page

Add a "Your groups" section listing the caller's memberships as horizontal cards. Each card shows: group name, role chip (Leader / Agent), and 3-4 quick action links inline (Profile / Wallet / Members / Reviews). Empty state suppresses the section entirely (no "No groups yet" placeholder; just absent).

### 5.7 Admin dashboard (`/admin`)

New "Groups" card alongside the existing fraud-flags / reports / disputes cards:

```tsx
<DashboardCard href="/admin/groups" icon={<UsersRound />} label="Groups" />
```

### 5.8 Notification bell + `/notifications` page

The `GROUP_INVITATION_RECEIVED` (or equivalent) notification category currently has an empty `linkUrl`. Populate it with `/groups/invitations/me` so:

- The bell-icon row is clickable.
- The full `/notifications` page row is clickable.
- The hover-state has the same URL for keyboard nav consistency.

The renderer (`NotificationRow` or equivalent) already reads `linkUrl` and makes rows clickable when present; no renderer change needed.

### 5.9 SL IM dispatcher

The body template for the invitation notification category appends:

> View at https://slpa.app/groups/invitations/me

(or the env-appropriate base URL via the existing template substitution). This way the recipient can click through from the SL email forwarding their IMs.

## 6. Backend endpoint surface

### 6.1 New: `GET /api/v1/realty-groups`

Public, anonymous-accessible. Returns `PagedResponse<RealtyGroupCardDto>`.

**Query parameters:**

- `q` (optional, string) — case-insensitive contains match on `name` + `description`.
- `page` (optional, int, default 0).
- `size` (optional, int, default 20, max 60).
- `sort` (optional, enum `RATING|NEWEST|MOST_ACTIVE_LISTINGS|MOST_SALES`, default `RATING`).

**Filters (always applied, not user-toggleable):**

- Verified groups only — `EXISTS (SELECT 1 FROM realty_group_sl_groups WHERE realty_group_id = g.id AND verified = TRUE)`.
- Suspended-excluded — `NOT EXISTS (SELECT 1 FROM realty_group_suspensions WHERE realty_group_id = g.id AND lifted_at IS NULL)`.
- Not dissolved — `dissolved_at IS NULL`.

**DTO:**

```java
public record RealtyGroupCardDto(
    UUID publicId,
    String name,
    String slug,
    String tagline,              // server-truncated description, max 120 chars + trailing ellipsis
    String logoUrl,              // relative; frontend wraps with apiUrl()
    String coverUrl,             // nullable
    OffsetDateTime foundedAt,    // == realty_groups.created_at
    int memberCount,
    int memberSeatLimit,
    int activeListingsCount,     // count of auctions WHERE realty_group_id=g.id AND status IN (SCHEDULED, LIVE)
    int completedSalesCount,     // count of auctions WHERE realty_group_id=g.id AND status=COMPLETED
    GroupRatingDto rating        // { averageRating: BigDecimal|null, reviewCount: int }
) {}
```

`hasVerifiedSlGroup` is NOT in the DTO — the listing-level filter makes it implicit (always true).

**Query implementation:** native SQL projection that joins `realty_group_sl_groups`, `realty_group_suspensions`, `auctions`, and the existing rating aggregate. A new repo method on `RealtyGroupRepository`:

```java
@Query(value = """
    SELECT
      g.public_id, g.name, g.slug, g.description, g.logo_url, g.cover_url,
      g.created_at, g.member_count, g.member_seat_limit,
      (SELECT count(*) FROM auctions a WHERE a.realty_group_id = g.id
                                         AND a.status IN ('SCHEDULED','LIVE')) AS active_listings,
      (SELECT count(*) FROM auctions a WHERE a.realty_group_id = g.id
                                         AND a.status = 'COMPLETED') AS completed_sales,
      (SELECT avg(r.rating) FROM reviews r JOIN auctions a ON r.auction_id = a.id
                                   WHERE a.realty_group_id = g.id) AS average_rating,
      (SELECT count(*) FROM reviews r JOIN auctions a ON r.auction_id = a.id
                                   WHERE a.realty_group_id = g.id) AS review_count
    FROM realty_groups g
    WHERE g.dissolved_at IS NULL
      AND EXISTS (SELECT 1 FROM realty_group_sl_groups s
                   WHERE s.realty_group_id = g.id AND s.verified = TRUE)
      AND NOT EXISTS (SELECT 1 FROM realty_group_suspensions sus
                       WHERE sus.realty_group_id = g.id AND sus.lifted_at IS NULL)
      AND (:q IS NULL OR LOWER(g.name) LIKE CONCAT('%', LOWER(:q), '%')
                       OR LOWER(g.description) LIKE CONCAT('%', LOWER(:q), '%'))
    """, nativeQuery = true)
Page<RealtyGroupCardRow> browseCards(@Param("q") String q, Pageable pageable);
```

Sort branches map `Pageable`'s `Sort` to one of: `rating DESC NULLS LAST`, `created_at DESC`, `active_listings DESC`, `completed_sales DESC`. Tie-breaker on `name ASC` for stable pagination.

`description` is truncated to 120 chars + ellipsis server-side before mapping to `tagline` in the service layer.

### 6.2 Verify / possibly new: `GET /api/v1/me/group-invitations`

The existing `/dashboard/invitations` page consumed a backend endpoint already; this spec's recipient page (`/groups/invitations/me`) reuses it. The migration verifies the endpoint exists and that the response shape carries everything the new page needs (inviter display name, group name + slug + logo, role-on-acceptance preview, expiry, decline URL).

If the existing endpoint is short any field the new page needs, extend it in the same PR. The migration also gates whether to introduce a `useMyGroupInvitations()` hook wrapper if not already present.

### 6.3 Slug validator update

Reserved-slug check added to `RealtyGroupService.createGroup` (or `RealtyGroupSlugFactory` — wherever the slug-validation chain lives):

```java
private static final Set<String> RESERVED_SLUGS = Set.of("new", "me", "invitations");

if (RESERVED_SLUGS.contains(slug)) {
    throw new ReservedSlugException(slug);
}
```

New exception class + handler mapping to 422 with code `RESERVED_SLUG`.

### 6.4 Notification + SL IM updates

`NotificationPublisher.invitationReceived(...)` (or whatever the invitation publish method is named) populates `linkUrl = "/groups/invitations/me"`. SL IM dispatcher template for the invitation category appends the URL line per §5.9.

## 7. Frontend integration constraints

### 7.1 Directory page `/groups`

- Page wrapper: `frontend/src/app/groups/page.tsx`, server component, `export const dynamic = "force-dynamic"`.
- Client component: `frontend/src/components/realty/browse/GroupsPage.tsx` imported from the claude.ai/design template.
- Rendered with the locked props: `<GroupsPage cardLayout="cover" sidebar="left" />`.
- Data layer: new `useBrowseGroups({ q, page, size, sort })` TanStack Query hook hitting the new endpoint.

### 7.2 Group sub-tree `/groups/[slug]/*`

- Persistent layout at `frontend/src/app/groups/[slug]/layout.tsx` renders the sub-nav per §5.4.
- Each sub-page is its own `page.tsx` in the corresponding directory:
  - `frontend/src/app/groups/[slug]/page.tsx` — public profile.
  - `frontend/src/app/groups/[slug]/profile/page.tsx` — editable profile form (former Profile tab body).
  - `frontend/src/app/groups/[slug]/members/page.tsx` (former Members tab body).
  - `frontend/src/app/groups/[slug]/invitations/page.tsx` (former Invitations tab body).
  - `frontend/src/app/groups/[slug]/settings/page.tsx` (former Settings tab body).
  - `frontend/src/app/groups/[slug]/wallet/page.tsx` (moved from `/realty/groups/[publicId]/wallet`).
  - `frontend/src/app/groups/[slug]/sl-groups/page.tsx` (moved).
  - `frontend/src/app/groups/[slug]/analytics/commissions/page.tsx` (moved).
  - `frontend/src/app/groups/[slug]/reviews/page.tsx` (moved).

The slug-keyed sub-pages need a slug→publicId resolution at the top of each page so the existing publicId-keyed services / hooks keep working unchanged. The layout already calls `useRealtyGroupBySlug(slug)` to render the sub-nav; sub-pages read the same query.

### 7.3 Deletions

The following directories and their `page.tsx` files are deleted in the migration:

- `frontend/src/app/dashboard/(verified)/groups/` (entire subtree)
- `frontend/src/app/dashboard/(verified)/invitations/`
- `frontend/src/app/realty/groups/` (entire subtree)
- `frontend/src/app/group/[slug]/`
- `frontend/src/app/admin/realty-groups/` (entire subtree — renamed to `/admin/groups/`)

### 7.4 Component link-target sweep

Every component with a hardcoded old-path link target gets updated in the migration PR. Known sites:

- `EditGroupAffordance.tsx` (`/dashboard/groups/[slug]/manage` → `/groups/[slug]/profile`)
- `GroupCreateForm.tsx` (post-create redirect `/dashboard/groups/[slug]/manage` → `/groups/[slug]/profile`)
- `SettingsTab.tsx` (post-dissolve redirect `/dashboard/groups` → `/groups/me`)
- `GroupRatingBadge.tsx` (`/realty/groups/[publicId]/reviews` → `/groups/[slug]/reviews` — note identifier flip)
- `GroupAttributionLine.tsx`, `GroupBadge.tsx`, `GroupChip.tsx`, `LeaderCard.tsx` (`/group/[slug]` → `/groups/[slug]`)
- `Header.tsx`, `MobileMenu.tsx`, `UserMenuDropdown.tsx` — add new links per §5
- `AdminDashboardPage.tsx` — add Groups card
- `AdminAuditLogTable.tsx` / row renderer — update any audit-log row that links to a realty-group admin page

## 8. Migration sequence (single PR, recommended commit order)

1. **Backend** — new browse endpoint + `RealtyGroupCardDto` + reserved-slug validator + notification `linkUrl` + SL IM body. Includes a new repo query.
2. **Frontend route scaffolding** — create the full `/groups/*` and `/admin/groups/*` directory tree with placeholder `page.tsx` files that re-export the existing tab bodies / sub-page components.
3. **Frontend route content** — port each old `page.tsx` to its new location; extract tab bodies into their own pages.
4. **Layout + sub-nav** — `/groups/[slug]/layout.tsx` with the persistent sub-nav.
5. **Nav surfaces** — `Header`, `UserMenuDropdown`, `MobileMenu`, admin dashboard card.
6. **Internal reference sweep** — every component with a hardcoded link target (per §7.4).
7. **Old-route deletion** — remove the four directories listed in §7.3.
8. **Docs + Postman** — update `docs/postman/realty-groups-g-additions.md` request paths, `docs/implementation/CONVENTIONS.md` if it mentions the old paths, `docs/implementation/DEFERRED_WORK.md` housekeeping if any item references old paths, `FOOTGUNS.md` if any entry references old paths.

The PR target is `dev` per project convention; `dev → main` requires explicit user authorization per `feedback_no_merge_to_main.md`.

## 9. Testing

### 9.1 Backend (`@SpringBootTest + @AutoConfigureMockMvc`)

- `GET /api/v1/realty-groups` happy paths per sort branch (RATING / NEWEST / MOST_ACTIVE_LISTINGS / MOST_SALES).
- Search match + miss.
- Pagination boundary (size > max → 400 or clamp; size = max → 60 rows).
- Verified-only filter: seed an unverified group + a verified group, assert only the verified one returns.
- Suspended-excluded filter: seed a suspended verified group, assert it's absent from results.
- `RealtyGroupCardDto` projection accuracy: aggregates match the seeded auction counts; rating reflects seeded reviews.
- Slug validator: each reserved slug throws `ReservedSlugException` mapped to 422 with code `RESERVED_SLUG`.
- Invitation notification: `linkUrl` is `/groups/invitations/me`.

### 9.2 Frontend (Vitest + MSW)

- New `page.tsx` smoke tests under `frontend/src/app/groups/*` (one per route): renders without throwing, hits the right MSW handler.
- Group sub-nav permission gating: covered by per-permission scenarios — non-leader sans `MANAGE_MEMBERS` does not see Analytics; leader sees every item; agent without `INVITE_AGENTS` does not see Invitations; non-member sees only the public profile + reviews.
- Non-member deep link redirect: navigating to `/groups/[slug]/wallet` as a non-member redirects to `/groups/[slug]`.
- Reserved-slug create rejection: `GroupCreateForm` surfaces the inline error.
- `Header`, `UserMenuDropdown`, `MobileMenu` link assertions: new items present and link correctly; auth-only items hidden when unauthenticated; invitation badge renders when pending count > 0.
- Notification row click target is `/groups/invitations/me` when the row's category is `GROUP_INVITATION_RECEIVED`.

### 9.3 Manual

- Postman: sweep — every old path removed, new browse request added, founder-terminal verify request stays at `/api/v1/sl/sl-group/verify`.
- Walk the click paths: log in → Header "Groups" → directory → card → public profile → sub-nav item → operational surface.
- Log in as an invited user → bell → invitation row → recipient page.

## 10. Acceptance criteria (PR-mergeable when)

- `./mvnw test` passes (no regression).
- `cd frontend && npm run verify` passes.
- `next build` succeeds with the new route tree.
- `git grep -E '/dashboard/groups|/realty/groups|/group/\[' frontend/src` returns zero hits (except inside this spec and historical specs/plans).
- `git grep '/admin/realty-groups' frontend/src` returns zero hits.
- Every nav surface in §5 has the documented link present (manual verification + Vitest assertions).
- The directory page loads search + sort working end-to-end against a freshly seeded dev backend.
- `<GroupsPage cardLayout="cover" sidebar="left" />` renders without prop warnings.
- Postman browse request runs green.

## 11. Out of scope (tracked separately)

- Slug rename / `slug_history` redirect table (current model: slugs are stable post-creation).
- HTTP redirects from old URLs (pre-launch posture; clean break).
- Renaming `/listings/create` to `/listings/new` for verb-style consistency.
- Public surface for showing unverified groups elsewhere (current rule: verified-only on `/groups`; direct link to `/groups/[slug]` still resolves unverified groups).
- Realty-group concept expanding beyond "groups that sell SL land" (no other "groups" feature on the roadmap that would force a namespace disambiguation).

## 12. References

- Sub-project G spec: `docs/superpowers/specs/2026-05-12-realty-groups-final-cleanup-design.md`
- `docs/postman/realty-groups-g-additions.md` (needs path updates)
- `docs/implementation/FOOTGUNS.md` §G.1–§G.4 (no changes; URLs not referenced)
- claude.ai/design template: `GroupsPage` component imported with `cardLayout="cover"` and `sidebar="left"` props.
