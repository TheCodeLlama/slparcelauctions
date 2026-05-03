#!/usr/bin/env python3
"""
Second-pass fixup for tests left in a half-migrated state by the first
migrator. We inject `UUID regionUuid = UUID.randomUUID();` into any method
that references regionUuid without declaring it, and we replace any
remaining `mapApi.resolveRegion(...)` stubs with the fetchRegionPage
equivalent.
"""
import re
import sys
from pathlib import Path


REGION_UUID_USE = re.compile(r'\bregionUuid\b')
REGION_UUID_DECLARE = re.compile(r'UUID\s+regionUuid\s*=')

# Replace any remaining mapApi.resolveRegion(any()).thenReturn(Mono.just(new GridCoordinates(...))) line.
# Three close parens needed: any() close + resolveRegion() close + when() close.
MAP_STUB = re.compile(
    r'when\(mapApi\.resolveRegion\([^)]*\)\)\)\.thenReturn\([^;]*\);',
    re.DOTALL,
)


def transform(src: str) -> str:
    out = src

    # Replace the lingering mapApi stubs with a fetchRegionPage stub.
    out = MAP_STUB.sub(
        'when(worldApi.fetchRegionPage(regionUuid)).thenReturn(\n'
        '                Mono.just(new RegionPageData(regionUuid, "Coniston", '
        '1014.0, 1014.0, "M_NOT")));',
        out,
    )

    # Also clean up stray @MockitoBean / @Mock SlMapApiClient lines and imports the
    # earlier passes might have missed (e.g., when the type alias differs).
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

    # If RegionPageData is referenced but not imported, add the import.
    if 'RegionPageData' in out and 'import com.slparcelauctions.backend.region.dto.RegionPageData;' not in out:
        # Anchor near other sl.dto imports.
        out = out.replace(
            'import com.slparcelauctions.backend.sl.dto.ParcelMetadata;',
            'import com.slparcelauctions.backend.region.dto.RegionPageData;\n'
            'import com.slparcelauctions.backend.sl.dto.ParcelMetadata;',
            1,
        )

    # Inject `UUID regionUuid = UUID.randomUUID();` into any method body that
    # uses regionUuid without declaring it. Walk method-by-method using a
    # paren/brace state machine.
    out = inject_region_uuid_decls(out)

    return out


def inject_region_uuid_decls(src: str) -> str:
    """For each method whose body references `regionUuid` but never declares
    it, inject a declaration as the first statement of the method body."""
    # Find every method-body opening brace heuristically: a `)` followed by
    # `{` after a method signature line. Then scan the matching closing brace.
    result = []
    i = 0
    while i < len(src):
        # Look for `) ... {` that begins a method body.
        match = re.search(r'\)\s*(?:throws[^{]*)?\{', src[i:])
        if not match:
            result.append(src[i:])
            break
        body_open = i + match.end() - 1  # index of `{`
        result.append(src[i:body_open + 1])
        # Find matching closing brace.
        depth = 1
        j = body_open + 1
        while j < len(src) and depth > 0:
            ch = src[j]
            if ch == '{':
                depth += 1
            elif ch == '}':
                depth -= 1
            j += 1
        body = src[body_open + 1:j - 1]
        # If body uses regionUuid but doesn't declare it, inject.
        if REGION_UUID_USE.search(body) and not REGION_UUID_DECLARE.search(body):
            indent = '        '
            inject = f'\n{indent}UUID regionUuid = UUID.randomUUID();'
            body = inject + body
        result.append(body)
        result.append('}')
        i = j

    return ''.join(result)


def main() -> int:
    files = [Path(p) for p in sys.argv[1:]]
    if not files:
        print('Usage: fixup_region_stubs.py <file1.java> ...', file=sys.stderr)
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
