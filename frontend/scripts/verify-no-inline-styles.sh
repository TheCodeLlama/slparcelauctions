#!/usr/bin/env bash
# Fails (exit 1) if any inline style={...} prop appears in src/components or
# src/app, EXCEPT for files explicitly allowlisted below.
#
# Allowlist policy: every entry is a concession. New entries require a comment
# citing a spec section that explains why Tailwind utility classes can't
# express the style. Keep the list short.

set -uo pipefail

dirs=()
[[ -d src/components ]] && dirs+=(src/components)
[[ -d src/app ]] && dirs+=(src/app)

if [[ ${#dirs[@]} -eq 0 ]]; then
  echo "verify:no-inline-styles — neither src/components nor src/app exists, skipping"
  exit 0
fi

# Allowlisted file paths. Each entry MUST have a comment above it citing the
# spec section that justifies the exception.
#
# - src/components/marketing/HeroFeaturedStack.tsx: dynamic z-index per card
#   index (3 - i) cannot be expressed as static Tailwind classes without
#   spawning a class for each possible index. See spec
#   docs/superpowers/specs/2026-05-01-frontend-redesign-design.md §6.
#
# - src/components/reviews/RatingSummary.tsx: SVG <stop> elements use the
#   `stopColor` presentation attribute to drive a linearGradient partial-fill.
#   `stopColor` is an SVG-only property with no Tailwind utility equivalent —
#   it cannot be expressed as a class on the <stop> element.
#
# - src/components/listing/draft-editor/EditablePhotoGallery.tsx and
#   src/components/listing/PhotoUploader.tsx: @dnd-kit/sortable's `useSortable`
#   hook returns `transform` (a CSSProperties.transform string) and
#   `transition` values that must be applied as inline styles to the
#   sortable item — those values change continuously during a drag and
#   cannot be precomputed into Tailwind classes. See spec
#   docs/superpowers/specs/2026-05-07-listing-image-reorder-and-real-preview-design.md.
#
# - src/components/admin/realty-groups/AdminRealtyGroupRowActionMenu.tsx:
#   the dropdown is portaled into <body> with position: fixed (via the
#   `fixed` utility class). Its top/left coordinates are runtime pixel
#   offsets computed at open-time from the trigger button's
#   getBoundingClientRect() and updated on scroll/resize -- the values
#   change continuously and can't be precomputed into a Tailwind class.
#   Same shape as the @dnd-kit transform exception above.
#
# - src/components/auction/ParcelMap.tsx: the hover tooltip tracks the
#   mouse cursor. Its left/top are runtime pixel offsets (cursor position
#   relative to the <figure> bounding rect) computed on every mousemove
#   event -- the values change continuously and can't be precomputed into
#   Tailwind classes. Same shape as the AdminRealtyGroupRowActionMenu
#   exception above. See spec
#   docs/superpowers/specs/2026-05-24-parcel-map-frontend-design.md §4.7.
#
# - src/components/auction/ParcelMap3DLegend.tsx: gradient bar background;
#   data-driven not a theme color. See spec
#   docs/superpowers/specs/2026-05-25-parcel-map-land-use-design.md.
#
# - src/components/auction/ParcelMapLandUseLegend.tsx: per-category swatch
#   background; data palette in landUseColors.ts. See spec
#   docs/superpowers/specs/2026-05-25-parcel-map-land-use-design.md.
allowlist=(
  "src/components/marketing/HeroFeaturedStack.tsx"
  "src/components/reviews/RatingSummary.tsx"
  "src/components/listing/draft-editor/EditablePhotoGallery.tsx"
  "src/components/listing/PhotoUploader.tsx"
  "src/components/admin/realty-groups/AdminRealtyGroupRowActionMenu.tsx"
  "src/components/auction/ParcelMap.tsx"
  "src/components/auction/ParcelMap3DLegend.tsx"
  "src/components/auction/ParcelMapLandUseLegend.tsx"
)

# Build a single grep -v pipeline that filters out all allowlisted paths.
# Each allowlist entry contributes one `-e "^<path>:"` anchor so grep -v
# only strips lines whose path prefix matches exactly.
filter_args=()
for path in "${allowlist[@]}"; do
  filter_args+=(-e "^${path}:")
done

matches=$(grep -rn 'style={' "${dirs[@]}" | grep -v "${filter_args[@]}")

if [[ -n "$matches" ]]; then
  echo "$matches"
  echo ""
  echo "FAIL: inline style={...} props found above. Use Tailwind utility classes instead."
  echo "If a decorative CSS feature is unavailable in Tailwind, add the file to"
  echo "the allowlist array in frontend/scripts/verify-no-inline-styles.sh with"
  echo "a comment citing the spec section that justifies the exception."
  exit 1
fi

echo "verify:no-inline-styles — OK (no inline styles in ${dirs[*]} beyond the documented allowlist)"
exit 0
