#!/usr/bin/env bash
# Fails (exit 1) if any inline style={...} prop appears in src/components or src/app.

set -uo pipefail

dirs=()
[[ -d src/components ]] && dirs+=(src/components)
[[ -d src/app ]] && dirs+=(src/app)

if [[ ${#dirs[@]} -eq 0 ]]; then
  echo "verify:no-inline-styles — neither src/components nor src/app exists, skipping"
  exit 0
fi

if grep -rn 'style={' "${dirs[@]}"; then
  echo ""
  echo "FAIL: inline style={...} props found above. Use Tailwind utility classes instead."
  exit 1
fi

echo "verify:no-inline-styles — OK (no inline styles in ${dirs[*]})"
exit 0
