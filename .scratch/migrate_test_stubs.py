#!/usr/bin/env python3
"""
One-shot migrator for PR-2 test stubs. Each integration test has a
`seedParcel()` (or analog) that:

  - imports SlMapApiClient + GridCoordinates
  - holds @MockitoBean SlMapApiClient mapApi
  - stubs worldApi.fetchParcel(uuid) -> Mono<ParcelMetadata>(...) with a
    canonical-maturity literal and world-meter coords
  - stubs mapApi.resolveRegion(any()) -> Mono<GridCoordinates>(world-meters)

After PR 2 we need:
  - drop both imports, add ParcelPageData + RegionPageData
  - drop the @MockitoBean line
  - rename fetchParcel -> fetchParcelPage and wrap the ParcelMetadata in
    a ParcelPageData(meta, regionUuid)
  - inject `null` for the new ownerName positional and clear the maturity
    literal to null (region-scoped now)
  - replace the mapApi stub with a fetchRegionPage stub returning a
    RegionPageData with region-unit coords (Sansara: 1014/1014 -> world
    259584/259584)

Anything the regex can't safely transform is left alone — those files get
hand-fixed afterwards. The goal is to handle the 80% common pattern.
"""

import re
import sys
from pathlib import Path

SANSARA_GRID = (1014.0, 1014.0)
NON_MAINLAND_GRID = (390.0, 390.0)


def transform(src: str) -> str:
    out = src

    # --- imports
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

    # Insert new imports after the existing sl.dto.ParcelMetadata import.
    if (
        'com.slparcelauctions.backend.sl.dto.ParcelPageData;' not in out
        and 'com.slparcelauctions.backend.sl.dto.ParcelMetadata;' in out
    ):
        out = out.replace(
            'import com.slparcelauctions.backend.sl.dto.ParcelMetadata;',
            'import com.slparcelauctions.backend.region.dto.RegionPageData;\n'
            'import com.slparcelauctions.backend.sl.dto.ParcelMetadata;\n'
            'import com.slparcelauctions.backend.sl.dto.ParcelPageData;',
            1,
        )

    # --- @MockitoBean lines
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

    # --- worldApi.fetchParcel -> worldApi.fetchParcelPage
    out = re.sub(r'\bfetchParcel\(', 'fetchParcelPage(', out)

    # --- The fetchParcelPage(...).thenReturn(Mono.just(new ParcelMetadata(...)))
    # rewrite. Only safe automated form: capture the ParcelMetadata constructor
    # arguments (as a single block possibly spanning lines) and wrap them. We
    # also patch the maturity positional and inject ownerName.
    pattern = re.compile(
        r'fetchParcelPage\((?P<key>[^)]+?)\)\)\.thenReturn\(\s*Mono\.just\(\s*new ParcelMetadata\((?P<args>.*?)\)\)\)',
        re.DOTALL,
    )

    def replace_fetch(m: re.Match) -> str:
        key = m.group('key')
        args = m.group('args')
        # Inject ownerName = null after the third positional arg ("agent" / "group").
        # Strategy: split on top-level commas and re-emit. Keeps it order-preserving.
        parts = split_top_level_commas(args)
        if len(parts) == 12:
            # Old shape: parcelUuid, ownerUuid, ownerType, parcelName, regionName,
            #            areaSqm, description, snapshotUrl, maturity, x, y, z
            new_parts = parts[:3] + ['null'] + parts[3:]
            # Clear maturity (now positional 9, formerly 8 -> shifted to 9)
            new_parts[9] = ' null'
        elif len(parts) == 13:
            # Already migrated — leave alone.
            new_parts = parts
        else:
            # Unknown shape, give up; the file gets hand-fixed.
            return m.group(0)
        rebuilt = ',\n                '.join(p.strip() for p in new_parts)
        return (
            f'fetchParcelPage({key})).thenReturn(\n'
            f'                Mono.just(new ParcelPageData(new ParcelMetadata(\n'
            f'                        {rebuilt}), regionUuid)))'
        )

    out = pattern.sub(replace_fetch, out)

    # --- replace mapApi.resolveRegion(...) with worldApi.fetchRegionPage(...)
    # The most common shape:
    #   when(mapApi.resolveRegion(any())).thenReturn(Mono.just(new GridCoordinates(X, Y)));
    # Replace with a fetchRegionPage stub that uses Sansara region-unit coords
    # by default; explicit calls that pass non-Mainland world meters (100000.0,
    # 100000.0) get the NON_MAINLAND_GRID rewrite.
    map_pattern = re.compile(
        r'when\(mapApi\.resolveRegion\([^)]*\)\)\.thenReturn\(\s*'
        r'Mono\.just\(\s*new GridCoordinates\(\s*([\d.]+)\s*,\s*([\d.]+)\s*\)\)\);',
        re.DOTALL,
    )

    def replace_map(m: re.Match) -> str:
        x_str, y_str = m.group(1), m.group(2)
        x_world = float(x_str)
        y_world = float(y_str)
        # 100000.0/100000.0 -> intentional non-Mainland test value
        if abs(x_world - 100000.0) < 1.0 and abs(y_world - 100000.0) < 1.0:
            gx, gy = NON_MAINLAND_GRID
        else:
            gx, gy = SANSARA_GRID
        return (
            f'when(worldApi.fetchRegionPage(regionUuid)).thenReturn(\n'
            f'                Mono.just(new RegionPageData(regionUuid, "Coniston", '
            f'{gx}, {gy}, "M_NOT")));'
        )

    out = map_pattern.sub(replace_map, out)

    return out


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


def main() -> int:
    files = [Path(p) for p in sys.argv[1:]]
    if not files:
        print('Usage: migrate_test_stubs.py <file1.java> <file2.java> ...', file=sys.stderr)
        return 1
    for path in files:
        original = path.read_text(encoding='utf-8')
        new = transform(original)
        if new != original:
            path.write_text(new, encoding='utf-8')
            print(f'transformed: {path}')
        else:
            print(f'unchanged:   {path}')
    return 0


if __name__ == '__main__':
    sys.exit(main())
