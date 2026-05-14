# Realty Groups — React/TSX export

A self-contained, Tailwind-CSS-only port of the Realty Groups page (directory + detail), suitable for dropping into a Next.js app router project.

## Layout

```
realty-groups/
  types.ts                 # Shared types (RealtyGroupCard, GroupRating, sort/layout enums, ...)
  mockData.ts              # 18 sample groups + leader/agents/reviews for local previewing
  lib/cn.ts                # cn(), formatFounded(), initialsOf()
  components/              # One file per leaf component
    Avatar.tsx
    Badge.tsx
    Btn.tsx
    Checkbox.tsx
    DetailRow.tsx
    EmptyGroups.tsx
    FilterGroup.tsx
    GroupCard.tsx          # Three layout variants: standard | compact | cover
    GroupCover.tsx
    GroupLogo.tsx
    MemberRow.tsx
    Pagination.tsx
    StarPicker.tsx         # Half-step interactive star filter
    StarRating.tsx         # Read-only stars + numeric badge
  pages/
    GroupsPage.tsx         # Directory: search, sort, sidebar filters, paginated card grid
    GroupDetailPage.tsx    # Single-group profile: cover, tabs, stats strip
  index.ts                 # Re-exports
```

Every component file starts with `"use client"` and uses named exports only.

## Styling

Tailwind v4 utilities are the only styling surface used. **No** inline `style={...}` props (except for sizing decorative SVG icons that need precise pixel sizes via `style={{ width, height }}` on `<Star />` in `StarRating` — these are not colors), no `<style>` blocks, no `.css` files.

### Color tokens

The components reference these utility classes — wire them up in your Tailwind theme:

| Class            | Purpose                              |
| ---------------- | ------------------------------------ |
| `bg-bg`          | Page background                      |
| `bg-bg-subtle`   | Recessed section background          |
| `bg-bg-muted`    | Chip/badge background                |
| `bg-surface-raised` | Card / input background           |
| `bg-brand`       | Primary brand fill (orange)          |
| `text-fg`        | Primary text                         |
| `text-fg-muted`  | Secondary text                       |
| `text-fg-subtle` | Tertiary / metadata text             |
| `text-brand`     | Brand-tinted text                    |
| `text-on-brand`  | Text on top of `bg-brand`            |
| `text-danger`    | Destructive text                     |
| `border-border`  | Default border                       |
| `border-border-strong` | Hover / focus border ramp      |

Example Tailwind v4 `@theme`:

```css
@theme {
  --color-bg: #FFFFFF;
  --color-bg-subtle: #FAFAF9;
  --color-bg-muted: #F4F4F2;
  --color-surface-raised: #FFFFFF;
  --color-brand: #E3631E;
  --color-fg: #18181B;
  --color-fg-muted: #52525B;
  --color-fg-subtle: #71717A;
  --color-on-brand: #FFFFFF;
  --color-danger: #B91C1C;
  --color-border: #E7E5E0;
  --color-border-strong: #D4D2CC;
}
```

No `dark:` variants are used — swap the token values in a `[data-theme="dark"]` selector or use `@theme dark { ... }`.

## Icons

All icons import from `lucide-react`. The pinned import list (so you can verify your dep is installed):

`ArrowDown`, `ArrowUp`, `Check`, `ChevronLeft`, `ChevronRight`, `Heart`, `Plus`, `Search`, `ShieldCheck`, `Star`, `Tag`, `User`, `Users`.

## Usage

```tsx
import {
  GroupsPage,
  REALTY_GROUPS,
  MOCK_LEADER,
  MOCK_AGENTS,
  MOCK_REVIEWS,
  GroupDetailPage,
  type RealtyGroupCard,
} from "@/realty-groups";

export default function GroupsRoute() {
  return (
    <GroupsPage
      groups={REALTY_GROUPS}
      cardLayout="standard"          // "standard" | "compact" | "cover"
      sidebar="left"                  // "left" | "right" | "hidden"
      perPage={12}
      onOpenGroup={(g) => router.push(`/groups/${g.slug}`)}
      onStartGroup={() => router.push("/groups/new")}
      onHome={() => router.push("/")}
    />
  );
}
```

The directory ships with only **verified** SL groups — the parent should pre-filter or the component will skip any group with `hasVerifiedSlGroup === false`.

## Wiring to a real backend

The `REALTY_GROUPS` array exports the shape your `/api/v1/realty-groups` endpoint should return as `content: RealtyGroupCardDto[]`. Replace the static array with a server fetch and pass it into `<GroupsPage groups={...}>`.

For the detail page, fetch a single `RealtyGroupCard` plus leader/agents/reviews and pass them in. `<GroupDetailPage>` does not fetch anything itself.
