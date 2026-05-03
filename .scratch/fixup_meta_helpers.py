#!/usr/bin/env python3
"""
Convert `private ParcelMetadata meta(...)` helpers (and their few sister
forms) into `ParcelPageData meta(...)` helpers so they slot directly into
worldApi.fetchParcelPage(...) stubs.
"""
import re
import sys
from pathlib import Path


HELPER_RE = re.compile(
    r'(\bprivate\s+(?:static\s+)?)ParcelMetadata\s+(meta)\b',
)


def transform(src: str) -> str:
    out = src
    if 'ParcelMetadata' not in out:
        return out

    # Promote helper return type to ParcelPageData.
    out = HELPER_RE.sub(r'\1ParcelPageData \2', out)

    # Wrap the body's `return new ParcelMetadata(...)` in a ParcelPageData.
    # Walk every `return new ParcelMetadata` occurrence and balance parens
    # to find the closing token.
    new = []
    i = 0
    needle = 'return new ParcelMetadata'
    while True:
        j = out.find(needle, i)
        if j < 0:
            new.append(out[i:])
            break
        new.append(out[i:j])
        # Open paren after the ident.
        k = j + len(needle)
        while k < len(out) and out[k] in ' \t\n\r':
            k += 1
        if k >= len(out) or out[k] != '(':
            new.append(out[j:k])
            i = k
            continue
        depth = 1
        m = k + 1
        while m < len(out) and depth > 0:
            ch = out[m]
            if ch == '(':
                depth += 1
            elif ch == ')':
                depth -= 1
            m += 1
        # m is one past the `)` of the ParcelMetadata constructor.
        # Insert wrapping at: replace `return new ParcelMetadata(...)` with
        # `return new ParcelPageData(new ParcelMetadata(...), UUID.randomUUID())`.
        ctor = out[j + len('return '):m]  # `new ParcelMetadata(...)`
        wrapped = f'return new ParcelPageData({ctor}, UUID.randomUUID())'
        new.append(wrapped)
        i = m

    return ''.join(new)


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
