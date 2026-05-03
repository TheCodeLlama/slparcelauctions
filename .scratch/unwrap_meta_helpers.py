#!/usr/bin/env python3
"""
Revert the over-eager wrap done by fixup_meta_helpers.py: change
`private ParcelPageData meta(...)` returning `new ParcelPageData(new
ParcelMetadata(...), UUID.randomUUID())` back to `private ParcelMetadata
meta(...)` returning `new ParcelMetadata(...)`.

The wrap was wrong because the call sites that need ParcelPageData wrap
them locally (e.g., `Mono.just(new ParcelPageData(meta(...), uuid))`).
Other call sites need the bare ParcelMetadata.
"""
import re
import sys
from pathlib import Path


def transform(src: str) -> str:
    out = src
    # Change return type: `private ParcelPageData meta` -> `private ParcelMetadata meta`
    out = re.sub(
        r'private(\s+(?:static\s+)?)ParcelPageData\s+meta\b',
        r'private\1ParcelMetadata meta',
        out,
    )
    # Unwrap return: `return new ParcelPageData(new ParcelMetadata(args), UUID.randomUUID());`
    # -> `return new ParcelMetadata(args);`
    out = re.sub(
        r'return new ParcelPageData\(new ParcelMetadata\((?P<args>.*?)\), UUID\.randomUUID\(\)\)',
        r'return new ParcelMetadata(\g<args>)',
        out,
        flags=re.DOTALL,
    )
    return out


def main() -> int:
    files = [Path(p) for p in sys.argv[1:]]
    if not files:
        return 1
    for path in files:
        original = path.read_text(encoding='utf-8')
        new = transform(original)
        if new != original:
            path.write_text(new, encoding='utf-8')
            print(f'transformed: {path}')
    return 0


if __name__ == '__main__':
    sys.exit(main())
