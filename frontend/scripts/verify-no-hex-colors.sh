#!/usr/bin/env bash
# Fails (exit 1) if a hex color literal appears inside a className or style attribute
# in src/components or src/app. Tokens are the single source of truth — see spec §4.

set -uo pipefail

dirs=()
[[ -d src/components ]] && dirs+=(src/components)
[[ -d src/app ]] && dirs+=(src/app)

if [[ ${#dirs[@]} -eq 0 ]]; then
  echo "verify:no-hex-colors — neither src/components nor src/app exists, skipping"
  exit 0
fi

if grep -rEn 'className.*#[0-9a-fA-F]{3,8}|style=.*#[0-9a-fA-F]{3,8}' "${dirs[@]}"; then
  echo ""
  echo "FAIL: hex color literals found in className/style attributes above."
  echo "Replace with token-driven Tailwind utilities (bg-primary, text-on-surface, etc.)."
  exit 1
fi

echo "verify:no-hex-colors — OK (no hex literals in className/style in ${dirs[*]})"
exit 0
