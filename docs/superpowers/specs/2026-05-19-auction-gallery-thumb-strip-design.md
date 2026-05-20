# Auction Gallery Thumbnail Strip

**Date:** 2026-05-19
**Status:** Awaiting user review.

## 1. Goal

Replace the asymmetric 3-cell hero grid in `frontend/src/components/auction/AuctionHero.tsx` with a single hero image + horizontal thumbnail strip directly beneath it. Click a thumb to swap the hero, click the hero to open the existing Lightbox at the currently-selected index, use `← / →` to navigate. Gallery stays inside the existing `lg:col-span-8` column — no layout change beyond the gallery component itself.

The Lightbox component is unchanged; only how it's invoked + seeded changes.

## 2. Interaction model

| Trigger | Effect |
|---|---|
| Click a thumb | Hero swaps to that photo. No Lightbox. |
| Click the hero | Lightbox opens at the currently-selected index. |
| `←` (page-level, gallery not in Lightbox) | Previous image; wraps from first to last. |
| `→` (page-level, gallery not in Lightbox) | Next image; wraps from last to first. |
| Swipe hero left (touch) | Next image (same as `→`). |
| Swipe hero right (touch) | Previous image (same as `←`). |
| Tap hero (touch, no swipe) | Lightbox opens at currently-selected index. |
| Lightbox open + arrow keys | Lightbox's existing handlers — no change. |
| Close Lightbox | Page restores at whatever index the Lightbox left off on. |

**Arrow-key activation rules:**
- Listener attached at `window` level on mount of `AuctionHero`, detached on unmount.
- Ignored if `event.target` is `<input>`, `<textarea>`, `<select>`, or `contentEditable`.
- Ignored if `event.metaKey || event.ctrlKey || event.altKey` (don't steal browser hotkeys).
- Ignored if the Lightbox is open. `AuctionHero` infers this from its own state: `lightboxIndex !== null`. Lightbox's own arrow-key handler is what runs in that case.

**Touch-swipe detection:**
- `pointerdown` records start `(x, y, t)`.
- `pointerup` checks horizontal delta > 30px AND vertical delta < 30px AND elapsed < 500ms.
- A pointerup that fails the swipe test counts as a tap → opens Lightbox.

## 3. Layout

### Desktop (`md:`+)

- Hero: full column width, height `380px` (down from the current `500px` asymmetric grid).
- Thumb row: `flex gap-2 overflow-x-auto` below the hero. Squares `64px`. Single row.
- Active thumb: `outline-2 outline-brand outline-offset-2`. Inactive thumbs: no opacity change (full opacity, no border).
- Edge fades: two `pointer-events-none` gradients on the left + right edges of the scroll container, conditionally visible based on `scrollLeft` and (`scrollWidth - clientWidth - scrollLeft`). Both can be active simultaneously when the user is in the middle of the strip.
- Counter overlay: dark pill `bottom-right` of the hero. Copy `"{selectedIndex + 1} / {total}"` — 1-indexed so the first photo reads `1 / N`.

### Mobile (<`md:`)

- Hero: full width, height `240px`.
- Thumb row: same `overflow-x-auto` strip, thumbs `56px`.
- Counter overlay: same.
- Touch swipe enabled on the hero (see §2).

### Removed

- Asymmetric 3-cell grid (1 large hero + 2 small secondaries).
- `+N more` overlay on the last secondary cell.

## 4. State model

`AuctionHero` owns two pieces of local state (inside the multi-photo branch):

- `selectedIndex: number` — source of truth for the hero render and the active-thumb outline. Default `0`.
- `lightboxIndex: number | null` — existing field. Reused. Seeded from `selectedIndex` on hero click; updated alongside `selectedIndex` on Lightbox `onIndexChange` callbacks so closing restores the page-level state.

**Defensive clamp:** mirrors the existing `effectiveLightboxIndex` pattern. Derive `effectiveSelectedIndex = selectedIndex < sorted.length ? selectedIndex : 0` at render time so a stale index from a soft navigation / photo-array change collapses to a safe default without a state-mutating effect.

**Auto-scroll-into-view:** whenever `selectedIndex` changes from a non-click source (arrow key, swipe), call `thumbButtonRefs[selectedIndex].scrollIntoView({ behavior: 'smooth', block: 'nearest', inline: 'center' })` so the active thumb is always visible. `block: 'nearest'` avoids stealing vertical page scroll.

## 5. Component structure

Single file: `frontend/src/components/auction/AuctionHero.tsx`. The existing no-photo / single-photo branches stay untouched. Rewrite the multi-photo branch in place. Pull two small internal subcomponents out for clarity — same file, not exported:

```ts
function HeroImage({ photo, index, total, onClick, onSwipeLeft, onSwipeRight }) { … }
function ThumbStrip({ photos, selectedIndex, onSelect }) { … }
```

The window-level keydown listener lives in `AuctionHero` itself via a single `useEffect`. The touch-swipe handlers live in `<HeroImage>` via inline `onPointerDown` / `onPointerUp`. Edge-fade visibility lives in `<ThumbStrip>` via a `useEffect` that listens for the container's `scroll` event and updates two boolean state slots (`hasLeftOverflow`, `hasRightOverflow`).

## 6. Accessibility

- Each thumb is a real `<button>`:
  - `aria-label="Show photo N"`
  - `aria-current="true"` when `i === selectedIndex`; absent otherwise.
- Hero `<button>` keeps `aria-label="Open photo"` (already present).
- Tab order: hero → thumb 1 → thumb 2 → … (natural DOM order).
- Arrow keys are visual-keyboard shortcuts; not announced. Screen-reader users navigate via tab + Enter.
- `scrollIntoView` uses `block: 'nearest'` so screen-reader scroll position isn't disturbed.
- `outline` (not `border`) for the active state to avoid layout shift on selection change.

## 7. Testing

Extend `frontend/src/components/auction/AuctionHero.test.tsx`. Don't remove existing single-photo / placeholder / snapshot cases — they're outside this rewrite's scope.

New cases for the multi-photo branch:

1. **Initial render** — hero shows photo 0; thumb 0 has `aria-current="true"`.
2. **Click thumb** — clicking thumb 2 swaps the hero `src` and moves `aria-current` to thumb 2.
3. **Click hero opens Lightbox at selected index** — select thumb 2, click hero, assert Lightbox renders with photo 2 as the initial image.
4. **ArrowRight advances** — `selectedIndex` increments; hero `src` updates.
5. **ArrowLeft retreats**.
6. **Wrap from last to first** — `ArrowRight` on last photo lands on photo 0.
7. **Wrap from first to last** — `ArrowLeft` on photo 0 lands on the last photo.
8. **Form-input guard** — render an `<input>` outside `AuctionHero`, focus it, dispatch `ArrowRight` → no-op (hero unchanged).
9. **Modifier-key guard** — `keydown ArrowRight` with `metaKey: true` → no-op.
10. **Lightbox-open guard** — open Lightbox, dispatch `ArrowRight` → page-level handler must not also fire (assert via spy on `setSelectedIndex` or via behavior).
11. **Photos shrink defensive** — start with 6 photos, `selectedIndex=4`; rerender with 3 photos → no crash, hero renders photo 0.
12. **`aria-current` exactly one** — after any navigation, exactly one thumb has `aria-current="true"`.
13. **Touch swipe left → next** — simulate `pointerdown` at `(200, 100)`, `pointerup` at `(120, 100)` within 200ms → `selectedIndex` advances.
14. **Touch swipe right → previous**.
15. **Tap (no horizontal delta) → opens Lightbox** — `pointerdown` and `pointerup` at the same position → Lightbox opens.
16. **Counter overlay** — assert the pill renders `"{selected+1} / {total}"` and updates when `selectedIndex` changes.

No backend changes — the photo DTO shape (`AuctionPhotoDto[]` sorted by `sortOrder`) is unchanged.

## 8. Out of scope

- Pinch-to-zoom inside the Lightbox (Lightbox is unchanged).
- Photo reordering UI for the seller.
- Lazy-loading thumbs past the visible strip (all photos render eagerly today; no change).
- Keyboard navigation *inside* the Lightbox (Lightbox owns that).
- Photo upload / capacity changes.

## 9. Decision log

Captured during brainstorming (2026-05-19):

- **Overflow** = horizontal scroll with bidirectional edge fade. Rejected wrap-to-second-row (layout shift on photo-heavy listings) and cap-with-`+N` tile (extra click on a strip we're already simplifying).
- **Mobile** = same desktop pattern + touch swipe on the hero. Rejected snap-scroll carousel (diverges from desktop, drops the thumb strip).
- **Active thumb** = outline-offset, no inactive fading. Rejected scale + underline (animation-heavy) and inset border (too subtle).
- **Arrow keys** = window-level with input/modifier guards. Rejected focus-scoped (discoverability) and viewport-scoped (added complexity for marginal gain).
- **Edge behaviour** = wrap around.
- **Lightbox opens at** = currently-selected index.
- **Counter** = pill overlay on the hero (stays visible regardless of strip scroll state).
