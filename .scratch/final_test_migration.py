#!/usr/bin/env python3
"""
Final pass for the six tests reset to dev tip — applies every pattern at
once: fetchParcel rename, ParcelPageData wrapping at every Mono.just(...)
inside a fetchParcelPage stub, owner-name injection in 12-arg
ParcelMetadata calls, and the SlMapApiClient/GridCoordinates cleanup.
"""
import re
import sys
from pathlib import Path


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


def find_call_args(src: str, open_idx: int):
    """Given the index of '(', return (args_str, end_idx) where end_idx is
    one past the matching ')'."""
    if src[open_idx] != '(':
        return None
    depth = 1
    i = open_idx + 1
    while i < len(src) and depth > 0:
        if src[i] == '(':
            depth += 1
        elif src[i] == ')':
            depth -= 1
        i += 1
    return (src[open_idx + 1:i - 1], i)


def transform(src: str) -> str:
    out = src

    # Drop SlMapApiClient and GridCoordinates imports.
    out = re.sub(
        r'^import com\.slparcelauctions\.backend\.sl\.SlMapApiClient;\n',
        '',
        out,
        flags=re.MULTILINE,
    )
    out = re.sub(
        r'^import com\.slparcelauctions\.backend\.sl\.dto\.GridCoordinates;\n',
        '',
        out,
        flags=re.MULTILINE,
    )

    # Drop @MockitoBean / @Mock SlMapApiClient lines.
    out = re.sub(
        r'^\s*@MockitoBean\s+SlMapApiClient\s+\w+;\s*\n',
        '',
        out,
        flags=re.MULTILINE,
    )
    out = re.sub(
        r'^\s*@Mock\s+SlMapApiClient\s+\w+;\s*\n',
        '',
        out,
        flags=re.MULTILINE,
    )

    # fetchParcel -> fetchParcelPage
    out = re.sub(r'\.fetchParcel\(', '.fetchParcelPage(', out)

    # Walk every `new ParcelMetadata(...)` and inject ownerName=null when 12 args.
    out = inject_owner_name_in_parcel_metadata(out)

    # Wrap every Mono.just(<X>) inside a fetchParcelPage(...).thenReturn(...).
    # Approach: find sequences `fetchParcelPage(<uuid>)).thenReturn(\n? Mono.just(<inner>))` and wrap.
    out = wrap_fetch_parcel_page_stubs(out)

    # Replace mapApi.resolveRegion(...) stubs with fetchRegionPage stubs.
    out = re.sub(
        r'when\(mapApi\.resolveRegion\([^)]*\)\)\)\.thenReturn\([^;]*\);',
        'when(worldApi.fetchRegionPage(any(java.util.UUID.class))).thenReturn(\n'
        '                Mono.just(new RegionPageData(java.util.UUID.randomUUID(), '
        '"Coniston", 1014.0, 1014.0, "M_NOT")));',
        out,
        flags=re.DOTALL,
    )
    # Some files use `mapApi.resolveRegion("Coniston")` with literal arg.
    out = re.sub(
        r'when\(mapApi\.resolveRegion\(("[^"]+")\)\)\.thenReturn\([^;]*\);',
        r'when(worldApi.fetchRegionPage(any(java.util.UUID.class))).thenReturn(\n'
        '                Mono.just(new RegionPageData(java.util.UUID.randomUUID(), '
        r'\1, 1014.0, 1014.0, "M_NOT")));',
        out,
        flags=re.DOTALL,
    )

    # Add ParcelPageData import if used and not yet imported.
    if 'ParcelPageData' in out and 'import com.slparcelauctions.backend.sl.dto.ParcelPageData;' not in out:
        out = out.replace(
            'import com.slparcelauctions.backend.sl.dto.ParcelMetadata;',
            'import com.slparcelauctions.backend.sl.dto.ParcelMetadata;\n'
            'import com.slparcelauctions.backend.sl.dto.ParcelPageData;',
            1,
        )
    if 'RegionPageData' in out and 'import com.slparcelauctions.backend.region.dto.RegionPageData;' not in out:
        out = out.replace(
            'import com.slparcelauctions.backend.sl.dto.ParcelMetadata;',
            'import com.slparcelauctions.backend.region.dto.RegionPageData;\n'
            'import com.slparcelauctions.backend.sl.dto.ParcelMetadata;',
            1,
        )

    return out


def inject_owner_name_in_parcel_metadata(src: str) -> str:
    out = []
    last = 0
    pos = 0
    needle = 'new ParcelMetadata'
    while True:
        i = src.find(needle, pos)
        if i < 0:
            break
        # Skip whitespace then expect `(`.
        j = i + len(needle)
        while j < len(src) and src[j] in ' \t\n\r':
            j += 1
        if j >= len(src) or src[j] != '(':
            pos = i + 1
            continue
        result = find_call_args(src, j)
        if result is None:
            pos = i + 1
            continue
        args, end = result
        parts = split_top_level_commas(args)
        if len(parts) == 12:
            new_parts = parts[:3] + [' null'] + parts[3:]
            out.append(src[last:j + 1])
            out.append(','.join(new_parts))
            out.append(')')
            last = end
        pos = end
    out.append(src[last:])
    return ''.join(out)


def wrap_fetch_parcel_page_stubs(src: str) -> str:
    """Wrap the argument of Mono.just(...) inside a fetchParcelPage(...)
    .thenReturn(...) chain with new ParcelPageData(arg, UUID.randomUUID()).
    Uses balanced-paren matching so meta(...) calls aren't split."""
    out = []
    last = 0
    pos = 0
    needle = '.thenReturn('
    while True:
        # Look for `fetchParcelPage(...)).thenReturn(` first, anchoring on
        # the prefix so we only touch fetchParcelPage callers.
        match = re.search(r'\.fetchParcelPage\([^)]*\)\)\s*\.thenReturn\(', src[pos:])
        if not match:
            break
        absolute_start = pos + match.start()
        thenReturn_open = pos + match.end() - 1  # index of `(`
        # Find the matching `)` of the thenReturn call.
        depth = 1
        i = thenReturn_open + 1
        while i < len(src) and depth > 0:
            if src[i] == '(':
                depth += 1
            elif src[i] == ')':
                depth -= 1
            i += 1
        thenReturn_close = i - 1
        body = src[thenReturn_open + 1:thenReturn_close]
        body_stripped = body.strip()
        # body should be `Mono.just(<arg>)` (possibly with surrounding whitespace).
        ms = re.match(r'(\s*)Mono\.just\(', body)
        if ms:
            mono_open = ms.end() - 1  # index of `(` of Mono.just
            depth = 1
            j = mono_open + 1
            while j < len(body) and depth > 0:
                if body[j] == '(':
                    depth += 1
                elif body[j] == ')':
                    depth -= 1
                j += 1
            arg = body[mono_open + 1:j - 1]
            arg_stripped = arg.strip()
            if not arg_stripped.startswith('new ParcelPageData('):
                # Wrap.
                new_body = (
                    f'\n                Mono.just(new ParcelPageData('
                    f'{arg_stripped}, java.util.UUID.randomUUID()))'
                )
                out.append(src[last:thenReturn_open + 1])
                out.append(new_body)
                last = thenReturn_close
        pos = thenReturn_close + 1
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
