#!/usr/bin/env bash
# Fails (exit 1) if a hex color literal appears inside a className or style attribute
# in src/components or src/app. Tokens are the single source of truth -- see spec §4.
#
# Allowlist policy: every entry is a concession. New entries require a comment
# citing a spec section that explains why token-driven Tailwind utilities can't
# express the color. Keep the list short.

set -uo pipefail

dirs=()
[[ -d src/components ]] && dirs+=(src/components)
[[ -d src/app ]] && dirs+=(src/app)

if [[ ${#dirs[@]} -eq 0 ]]; then
  echo "verify:no-hex-colors — neither src/components nor src/app exists, skipping"
  exit 0
fi

# Allowlisted file paths. Each entry MUST have a comment above it citing the
# spec section that justifies the exception.
#
# - src/components/inworld/FeaturedBoardView.tsx,
#   src/components/inworld/FeaturedBoardCycler.tsx,
#   src/components/inworld/PlaceholderBoardView.tsx,
#   src/app/in-world/board/[boardIndex]/page.tsx,
#   src/app/in-world/board/placeholder/page.tsx:
#   These are MOAP (Media On A Prim) pages rendered inside a 512x512 px
#   SL texture surface with no browser chrome. All layout, typography,
#   gradients, and color work is pixel-exact to the in-world render target
#   and cannot use the site design-token palette -- the colors are specific
#   to the in-world dark-canvas aesthetic defined in the spec. See spec
#   docs/superpowers/specs/2026-06-01-hq-featured-boards-design.md §5.3.
allowlist=(
  "src/components/inworld/FeaturedBoardView.tsx"
  "src/components/inworld/FeaturedBoardCycler.tsx"
  "src/components/inworld/PlaceholderBoardView.tsx"
  "src/app/in-world/board/[boardIndex]/page.tsx"
  "src/app/in-world/board/placeholder/page.tsx"
)

# Build a single grep -v pipeline that filters out all allowlisted paths.
filter_args=()
for path in "${allowlist[@]}"; do
  filter_args+=(-e "^${path}:")
done

matches=$(grep -rEn 'className.*#[0-9a-fA-F]{3,8}|style=.*#[0-9a-fA-F]{3,8}' "${dirs[@]}" | grep -v "${filter_args[@]}")

if [[ -n "$matches" ]]; then
  echo "$matches"
  echo ""
  echo "FAIL: hex color literals found in className/style attributes above."
  echo "Replace with token-driven Tailwind utilities (bg-primary, text-on-surface, etc.)."
  exit 1
fi

echo "verify:no-hex-colors — OK (no hex literals in className/style in ${dirs[*]} beyond the documented allowlist)"
exit 0
