#!/usr/bin/env python3
"""
Final pass: walk every `new ParcelMetadata(...)` constructor in test code
and inject a `null` at position 4 (ownerName) when the constructor has 12
positional args (the pre-PR-2 shape). 13-arg calls are left alone.
"""
import sys
from pathlib import Path


def find_constructor_args(src: str, start: int):
    """Given the start index of `new ParcelMetadata(`, return (args_start,
    args_end) — the slice of `src` containing the comma-separated argument
    list inside the parens. Returns None if start doesn't point to the
    expected token."""
    open_idx = src.find('(', start)
    if open_idx < 0:
        return None
    depth = 1
    i = open_idx + 1
    while i < len(src) and depth > 0:
        ch = src[i]
        if ch == '(':
            depth += 1
        elif ch == ')':
            depth -= 1
        i += 1
    if depth != 0:
        return None
    return (open_idx + 1, i - 1)


def split_top_level_commas(s: str) -> list[str]:
    parts: list[str] = []
    depth = 0
    start = 0
    for i, ch in enumerate(s):
        if ch in '([{':
            depth += 1
        elif ch in ')]}':
            depth -= 1
        elif ch == ',' and depth == 0:
            parts.append(s[start:i])
            start = i + 1
    parts.append(s[start:])
    return parts


def transform(src: str) -> str:
    needle = 'new ParcelMetadata'
    out = []
    last = 0
    pos = 0
    while True:
        i = src.find(needle, pos)
        if i < 0:
            break
        # Position of constructor open paren is i + len(needle) (whitespace
        # tolerated).
        head_start = i + len(needle)
        # Skip whitespace before `(`.
        j = head_start
        while j < len(src) and src[j] in ' \t\n\r':
            j += 1
        if j >= len(src) or src[j] != '(':
            pos = i + 1
            continue
        bounds = find_constructor_args(src, j)
        if bounds is None:
            pos = i + 1
            continue
        args_start, args_end = bounds
        args = src[args_start:args_end]
        parts = split_top_level_commas(args)
        if len(parts) == 12:
            # Inject null at position 4 (after the 3rd comma).
            new_parts = parts[:3] + [' null'] + parts[3:]
            new_args = ','.join(new_parts)
            out.append(src[last:args_start])
            out.append(new_args)
            last = args_end
        pos = args_end + 1

    out.append(src[last:])
    return ''.join(out)


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
