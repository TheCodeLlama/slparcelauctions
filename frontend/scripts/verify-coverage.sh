#!/usr/bin/env bash
# Fails (exit 1) if any src/components/ui/*.tsx is missing a sibling .test.tsx.
# Excludes barrel files (index, icons) which are not testable on their own.

set -euo pipefail

missing=0
for f in src/components/ui/*.tsx; do
  base="$(basename "$f" .tsx)"
  case "$base" in
    index|icons|*.test) continue ;;
  esac
  if [[ ! -f "src/components/ui/${base}.test.tsx" ]]; then
    echo "MISSING TEST: $f"
    missing=1
  fi
done

if [[ "$missing" -eq 1 ]]; then
  exit 1
fi
echo "All UI primitives have sibling test files."
