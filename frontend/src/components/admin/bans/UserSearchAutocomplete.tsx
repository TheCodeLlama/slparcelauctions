"use client";
import { useState, useRef, useEffect } from "react";
import { useUserSearch } from "@/hooks/admin/useUserSearch";
import type { AdminUserSummary } from "@/lib/admin/types";

type Props = {
  onSelect: (user: AdminUserSummary) => void;
  placeholder?: string;
};

export function UserSearchAutocomplete({ onSelect, placeholder = "Search by name or email…" }: Props) {
  const [query, setQuery] = useState("");
  const [closedForQuery, setClosedForQuery] = useState("");
  const containerRef = useRef<HTMLDivElement>(null);

  const { data, isFetching } = useUserSearch(query);
  const results = data?.content ?? [];

  const open = closedForQuery !== query && query.length >= 2 && results.length > 0;

  useEffect(() => {
    function handleClickOutside(e: MouseEvent) {
      if (containerRef.current && !containerRef.current.contains(e.target as Node)) {
        setClosedForQuery(query);
      }
    }
    document.addEventListener("mousedown", handleClickOutside);
    return () => document.removeEventListener("mousedown", handleClickOutside);
  }, [query]);

  function handleSelect(user: AdminUserSummary) {
    const name = user.displayName ?? user.email;
    setQuery(name);
    setClosedForQuery(name);
    onSelect(user);
  }

  return (
    <div ref={containerRef} className="relative w-full">
      <input
        type="text"
        value={query}
        onChange={(e) => setQuery(e.target.value)}
        onFocus={() => setClosedForQuery("")}
        placeholder={placeholder}
        data-testid="user-search-input"
        className="w-full rounded-lg bg-bg-muted px-4 py-2 text-sm text-fg placeholder:text-fg-muted ring-1 ring-border-subtle focus:outline-none focus:ring-2 focus:ring-brand"
      />
      {isFetching && (
        <div className="absolute right-3 top-2.5 text-fg-muted text-[11px]">…</div>
      )}
      {open && (
        <ul
          role="listbox"
          data-testid="user-search-dropdown"
          className="absolute z-50 left-0 right-0 mt-1 rounded-lg bg-bg-subtle border border-border-subtle shadow-md overflow-hidden"
        >
          {results.map((user) => (
            <li
              key={user.id}
              role="option"
              aria-selected={false}
              onClick={() => handleSelect(user)}
              data-testid={`user-option-${user.id}`}
              className="flex flex-col px-4 py-2 cursor-pointer hover:bg-bg-muted transition-colors"
            >
              <span className="text-sm font-medium text-fg">
                {user.displayName ?? "(no display name)"}
              </span>
              <span className="text-[11px] text-fg-muted">{user.email}</span>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
