#!/usr/bin/env bash
# Fails (exit 1) if any `dark:` Tailwind variant is found in src/components or src/app.
# The rule is enforced by absence: components must not write dark: variants because
# the .dark CSS override block in globals.css already swaps tokens. See spec §2.11.

set -uo pipefail

dirs=()
[[ -d src/components ]] && dirs+=(src/components)
[[ -d src/app ]] && dirs+=(src/app)

if [[ ${#dirs[@]} -eq 0 ]]; then
  echo "verify:no-dark-variants — neither src/components nor src/app exists, skipping"
  exit 0
fi

if grep -rEn 'dark:' "${dirs[@]}"; then
  echo ""
  echo "FAIL: dark: variants found above. See spec §2.11 — components must not write dark: variants."
  exit 1
fi

echo "verify:no-dark-variants — OK (no dark: variants found in ${dirs[*]})"
exit 0
