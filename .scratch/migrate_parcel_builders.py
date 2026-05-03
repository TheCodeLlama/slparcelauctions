#!/usr/bin/env python3
"""
Drops deprecated Parcel.builder() chained setters and routes their values
into a TestRegions helper call so the @ManyToOne FK is satisfied.

Mapping captured per chain:
  .regionName("X")        ->   name     for TestRegions.of(name, ...)
  .maturityRating("Y")    ->   maturity for TestRegions.of(...)
  .gridX(N), .gridY(N)    ->   grid coords (multiplied where needed elsewhere)
  .continentName(...)     ->   dropped entirely (we don't store continent)

If only the name is present, emit TestRegions.named("X").
If only an opinionated subset is present, fall back to TestRegions.mainland().

Uses a balanced-paren state machine so multi-line setters with nested method
calls (UUID.randomUUID(), OffsetDateTime.now(), etc.) don't break the match.
"""
import re
import sys
from pathlib import Path

DEPRECATED_FIELDS = ('regionName', 'gridX', 'gridY', 'continentName', 'maturityRating')


def find_builder_blocks(src: str):
    needle = 'Parcel.builder()'
    pos = 0
    while True:
        i = src.find(needle, pos)
        if i < 0:
            return
        start = i
        j = i + len(needle)
        while True:
            while j < len(src) and src[j] in ' \t\n\r':
                j += 1
            if j >= len(src) or src[j] != '.':
                break
            name_start = j + 1
            k = name_start
            while k < len(src) and (src[k].isalnum() or src[k] == '_'):
                k += 1
            if k == name_start or k >= len(src) or src[k] != '(':
                break
            method_name = src[name_start:k]
            depth = 0
            m = k
            while m < len(src):
                if src[m] == '(':
                    depth += 1
                elif src[m] == ')':
                    depth -= 1
                    if depth == 0:
                        break
                m += 1
            if m >= len(src):
                break
            j = m + 1
            if method_name == 'build':
                yield (start, j)
                break
        pos = j


def parse_setter(text: str, i: int):
    """If text[i:] starts with `.<name>(<args>)`, return (name, args, end_idx).
    Otherwise return (None, None, i)."""
    if i >= len(text) or text[i] != '.':
        return (None, None, i)
    name_start = i + 1
    k = name_start
    while k < len(text) and (text[k].isalnum() or text[k] == '_'):
        k += 1
    if k == name_start or k >= len(text) or text[k] != '(':
        return (None, None, i)
    name = text[name_start:k]
    depth = 0
    m = k
    while m < len(text):
        if text[m] == '(':
            depth += 1
        elif text[m] == ')':
            depth -= 1
            if depth == 0:
                break
        m += 1
    if m >= len(text):
        return (None, None, i)
    args = text[k + 1:m]
    return (name, args, m + 1)


def transform_chain(text: str) -> tuple[str, bool]:
    """Drop deprecated setters; collect their string values; re-emit a single
    .region(TestRegions...) call before the rest. Return (new_text, injected)."""
    captured = {}
    has_region = False

    # Walk the chain and split into kept and dropped setters.
    kept_pieces = []
    i = 0
    out_pieces = []
    while i < len(text):
        # Pass-through whitespace/comments/non-`.` text.
        if text[i] != '.':
            kept_pieces.append(text[i])
            i += 1
            continue
        name, args, end = parse_setter(text, i)
        if name is None:
            kept_pieces.append(text[i])
            i += 1
            continue
        if name in DEPRECATED_FIELDS:
            captured[name] = args.strip()
            i = end
            # Eat trailing whitespace up to and including a single newline so
            # the dropped setter doesn't leave a hole.
            while i < len(text) and text[i] in ' \t':
                i += 1
            if i < len(text) and text[i] == '\n':
                i += 1
            continue
        if name == 'region':
            has_region = True
        kept_pieces.append(text[i:end])
        i = end

    rebuilt = ''.join(kept_pieces)
    injected = False

    if not has_region:
        injected = True
        helper = render_helper(captured)
        # Inject right after `Parcel.builder()`.
        idx = rebuilt.find('Parcel.builder()') + len('Parcel.builder()')
        # Pick an indent that matches the next chained setter, falling back
        # to a sensible default.
        indent = pick_chain_indent(rebuilt[idx:])
        rebuilt = (
            rebuilt[:idx]
            + f'\n{indent}.region({helper})'
            + rebuilt[idx:]
        )

    return rebuilt, injected


def pick_chain_indent(rest: str) -> str:
    m = re.search(r'\n([ \t]+)\.\w+\(', rest)
    if m:
        return m.group(1)
    return '                '


def render_helper(captured: dict) -> str:
    # Default to mainland() so every call gets a unique random region name.
    # The original `.regionName("Coniston")` etc. was almost always a fixture
    # placeholder, not an assertion target. Tests that actually depend on a
    # specific region name fail loudly and get hand-fixed afterwards rather
    # than colliding on the regions.name unique constraint.
    return 'TestRegions.mainland()'


def strip_quotes(s):
    if s is None:
        return None
    s = s.strip()
    if s.startswith('"') and s.endswith('"'):
        return s[1:-1]
    return None


def transform(src: str) -> str:
    blocks = list(find_builder_blocks(src))
    if not blocks:
        return src

    out_parts = []
    last = 0
    needs_helper_import = False
    for start, end in blocks:
        out_parts.append(src[last:start])
        new_chain, injected = transform_chain(src[start:end])
        out_parts.append(new_chain)
        if injected:
            needs_helper_import = True
        last = end
    out_parts.append(src[last:])
    out = ''.join(out_parts)

    if needs_helper_import and 'TestRegions' not in src.split('class ')[0]:
        anchor = '\nclass '
        idx = out.find(anchor)
        if idx > 0:
            import_anchor = 'import com.slparcelauctions.backend.'
            last_import = out.rfind(import_anchor, 0, idx)
            if last_import >= 0:
                eol = out.find('\n', last_import)
                if eol >= 0:
                    out = (
                        out[: eol + 1]
                        + 'import com.slparcelauctions.backend.testsupport.TestRegions;\n'
                        + out[eol + 1 :]
                    )

    return out


def main() -> int:
    files = [Path(p) for p in sys.argv[1:]]
    if not files:
        print('Usage: migrate_parcel_builders.py <file1.java> ...', file=sys.stderr)
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
