#!/usr/bin/env bash
# Fails (exit 1) if any `dark:` Tailwind variant is found in src/components or src/app.
# The rule is enforced by absence: components must not write dark: variants because
# the .dark CSS override block in globals.css already swaps tokens. See spec §2.11.
#
# The pattern `dark:[a-zA-Z0-9_-]` matches Tailwind variants like `dark:bg-black`,
# `dark:text-white`, `dark:hover:opacity-50` — because they always have a class
# character immediately after the colon. It deliberately does NOT match JavaScript
# object property syntax like `dark: string;` or `{ dark: "/foo.png" }` (space or
# punctuation after the colon, not a class character). This distinction was added
# when FeatureCard's `backgroundImage: { light, dark }` prop shape made the older
# naive `dark:` match produce false positives.

set -uo pipefail

dirs=()
[[ -d src/components ]] && dirs+=(src/components)
[[ -d src/app ]] && dirs+=(src/app)

if [[ ${#dirs[@]} -eq 0 ]]; then
  echo "verify:no-dark-variants — neither src/components nor src/app exists, skipping"
  exit 0
fi

if grep -rEn 'dark:[a-zA-Z0-9_-]' "${dirs[@]}"; then
  echo ""
  echo "FAIL: dark: variants found above. See spec §2.11 — components must not write dark: variants."
  exit 1
fi

echo "verify:no-dark-variants — OK (no dark: variants found in ${dirs[*]})"
exit 0
