"use client";

import { useCallback, useEffect, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { useBrowseGroups } from "@/hooks/realty/useBrowseGroups";
import type { GroupsSortKey } from "@/lib/api/realtyGroupsBrowse";
import type { RealtyGroupCard } from "@/types/realty";
import { GroupsPage } from "./GroupsPage";

/**
 * Debounce window (ms) between a search keystroke and the URL write. Long
 * enough to coalesce a typical word-typing burst into one network round-trip,
 * short enough that the user perceives the filter as "live".
 */
const SEARCH_DEBOUNCE_MS = 300;

const VALID_SORTS: ReadonlySet<GroupsSortKey> = new Set<GroupsSortKey>([
  "RATING",
  "NEWEST",
  "MOST_ACTIVE_LISTINGS",
  "MOST_SALES",
]);

function parseSort(raw: string | null): GroupsSortKey {
  if (raw && (VALID_SORTS as Set<string>).has(raw)) {
    return raw as GroupsSortKey;
  }
  return "RATING";
}

function parsePage(raw: string | null): number {
  if (!raw) return 0;
  const n = Number.parseInt(raw, 10);
  return Number.isFinite(n) && n >= 0 ? n : 0;
}

/**
 * URL-state wrapper for the {@code /groups} directory (spec section 7.1).
 *
 * <p>Owns the {@code ?q=...&sort=...&page=...} contract: reads the params off
 * {@link useSearchParams}, calls {@link useBrowseGroups} for data, threads
 * both into {@link GroupsPage} with the locked layout props
 * ({@code cardLayout="cover"}, {@code sidebar="left"}).
 *
 * <p>URL writes follow {@code dev/null}-style cleanliness: params that equal
 * their default ({@code q=""}, {@code sort=RATING}, {@code page=0}) are
 * stripped so the canonical home of the directory is just {@code /groups}.
 *
 * <p>Search keystrokes are debounced ({@value SEARCH_DEBOUNCE_MS}ms) so a
 * typing burst yields one URL write rather than one per character. The input
 * stays controlled by a local state to keep keystrokes responsive while the
 * URL catches up. {@code router.replace} for the search write avoids a back-
 * button entry per character; sort + pagination + card-click use
 * {@code router.push} (user-meaningful navigation).
 */
export function GroupsBrowseClient() {
  const router = useRouter();
  const params = useSearchParams();

  const q = params.get("q") ?? "";
  const sort = parseSort(params.get("sort"));
  const page = parsePage(params.get("page"));

  const query = useBrowseGroups({ q, page, size: 20, sort });

  // Local input mirror: seeded from the URL, debounced back to the URL on
  // change. The URL is the source of truth; this state only buffers
  // keystrokes between debounce flushes. `seededQ` tracks the URL value the
  // input was last seeded from so an external URL change (back/forward nav,
  // or a sort/page write that does not touch `q`) re-seeds the input without
  // clobbering an in-flight user typing burst.
  const [inputValue, setInputValue] = useState(q);
  const [seededQ, setSeededQ] = useState(q);
  if (seededQ !== q && inputValue === seededQ) {
    // Render-phase sync (idiomatic for derived state per React docs): the
    // URL changed externally and the user has not typed since the last seed,
    // so adopt the new URL value.
    setSeededQ(q);
    setInputValue(q);
  }

  const writeParams = useCallback(
    (mutator: (sp: URLSearchParams) => void, mode: "push" | "replace") => {
      const sp = new URLSearchParams(params.toString());
      mutator(sp);
      // Strip defaults so the canonical /groups URL stays clean.
      if (sp.get("sort") === "RATING") sp.delete("sort");
      if (sp.get("page") === "0") sp.delete("page");
      if ((sp.get("q") ?? "") === "") sp.delete("q");
      const qs = sp.toString();
      const target = qs ? `/groups?${qs}` : "/groups";
      if (mode === "push") router.push(target);
      else router.replace(target);
    },
    [params, router],
  );

  // Debounced URL write for the search input. The local `inputValue` drives
  // the controlled input; this effect mirrors the debounced value into the
  // URL, deleting `page` so a new search starts on page 0.
  useEffect(() => {
    // No-op if the URL is already in sync (covers the initial mount and the
    // URL-changed-externally case where we just re-synced `inputValue`).
    if (inputValue === q) return;
    const handle = setTimeout(() => {
      writeParams((sp) => {
        if (inputValue.trim().length === 0) sp.delete("q");
        else sp.set("q", inputValue);
        sp.delete("page");
      }, "replace");
    }, SEARCH_DEBOUNCE_MS);
    return () => clearTimeout(handle);
  }, [inputValue, q, writeParams]);

  const handleQChange = useCallback((next: string) => {
    setInputValue(next);
  }, []);

  const handleSortChange = useCallback(
    (next: GroupsSortKey) => {
      writeParams((sp) => {
        sp.set("sort", next);
        sp.delete("page");
      }, "push");
    },
    [writeParams],
  );

  const handlePageChange = useCallback(
    (next: number) => {
      writeParams((sp) => {
        sp.set("page", String(next));
      }, "push");
    },
    [writeParams],
  );

  const handleOpenGroup = useCallback(
    (g: RealtyGroupCard) => {
      router.push(`/groups/${g.slug}`);
    },
    [router],
  );

  const handleStartGroup = useCallback(() => {
    router.push("/groups/new");
  }, [router]);

  if (query.isError) {
    return (
      <div
        data-testid="groups-browse-client"
        className="w-full max-w-[1280px] mx-auto px-6 py-7 pb-16"
      >
        <div
          role="alert"
          className="mx-auto max-w-2xl my-12 rounded-md border border-border bg-surface-raised p-6 text-sm text-fg"
        >
          <p className="font-semibold mb-1">Couldn&rsquo;t load groups.</p>
          <p className="text-fg-muted">
            Try refreshing the page. If the problem keeps happening, check back
            in a few minutes.
          </p>
        </div>
      </div>
    );
  }

  const data = query.data;

  // `BrowseGroupCard` is structurally identical to `RealtyGroupCard` — the two
  // names are an artifact of Tasks 8 and 10 landing in parallel (the Task 10
  // hook returns the inlined type; Task 8 added the canonical type at
  // `@/types/realty`). Cast at the boundary so `GroupsPage` (which speaks the
  // canonical type) gets the array shape it expects.
  const cards = (data?.content ?? []) as unknown as RealtyGroupCard[];

  return (
    <div data-testid="groups-browse-client">
      <GroupsPage
        cardLayout="cover"
        sidebar="left"
        groups={cards}
        q={inputValue}
        onQChange={handleQChange}
        sort={sort}
        onSortChange={handleSortChange}
        page={page}
        pageCount={data?.totalPages ?? 0}
        totalCount={data?.totalElements ?? 0}
        onPageChange={handlePageChange}
        onOpenGroup={handleOpenGroup}
        onStartGroup={handleStartGroup}
        isLoading={query.isPending}
      />
    </div>
  );
}
