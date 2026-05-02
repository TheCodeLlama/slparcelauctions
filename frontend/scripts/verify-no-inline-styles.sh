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
allowlist=(
  "src/components/marketing/HeroFeaturedStack.tsx"
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
