"use client";
import { useState, useEffect, useRef } from "react";
import { useAdminUserTypeahead } from "@/hooks/admin/useAdminLedger";

type Props = {
  selected?: { publicId: string; label: string };
  onSelect: (selection: { publicId: string; label: string } | null) => void;
};

export function UserTypeahead({ selected, onSelect }: Props) {
  const [query, setQuery] = useState("");
  const [debouncedQuery, setDebouncedQuery] = useState("");
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);

  // 200ms debounce
  useEffect(() => {
    const t = setTimeout(() => setDebouncedQuery(query.trim()), 200);
    return () => clearTimeout(t);
  }, [query]);

  useEffect(() => {
    if (!open) return;
    function onDocClick(e: MouseEvent) {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false);
    }
    document.addEventListener("mousedown", onDocClick);
    return () => document.removeEventListener("mousedown", onDocClick);
  }, [open]);

  const { data, isLoading } = useAdminUserTypeahead(debouncedQuery, open && !selected);

  if (selected) {
    return (
      <div className="inline-flex items-center gap-1 px-2 py-1 rounded-lg bg-info-bg text-info text-[12px]">
        <span data-testid="user-typeahead-selected">{selected.label}</span>
        <button
          type="button"
          onClick={() => onSelect(null)}
          aria-label="Clear user filter"
          className="text-info hover:text-fg ml-1"
          data-testid="user-typeahead-clear"
        >
          ×
        </button>
      </div>
    );
  }

  return (
    <div ref={ref} className="relative">
      <input
        type="text"
        placeholder="Filter by user…"
        value={query}
        onChange={(e) => { setQuery(e.target.value); setOpen(true); }}
        onFocus={() => setOpen(true)}
        data-testid="user-typeahead-input"
        className="w-48 rounded-lg bg-bg-muted px-3 py-2 text-sm text-fg placeholder:text-fg-muted ring-1 ring-border-subtle focus:outline-none focus:ring-2 focus:ring-brand"
      />
      {open && debouncedQuery.length >= 2 && (
        <div
          className="absolute left-0 top-full mt-1 z-30 w-72 rounded-lg bg-bg-subtle border border-border-subtle shadow-md max-h-72 overflow-y-auto"
          data-testid="user-typeahead-menu"
        >
          {isLoading && (
            <div className="px-3 py-2 text-[11px] text-fg-muted">Searching…</div>
          )}
          {!isLoading && data && data.content.length === 0 && (
            <div className="px-3 py-2 text-[11px] text-fg-muted">No users match.</div>
          )}
          {!isLoading && data && data.content.map((u) => (
            <button
              key={u.publicId}
              type="button"
              onClick={() => {
                onSelect({
                  publicId: u.publicId,
                  label: `${u.displayName ?? u.username} (${u.publicId.slice(0, 8)}…)`,
                });
                setQuery("");
                setOpen(false);
              }}
              data-testid={`user-typeahead-option-${u.publicId}`}
              className="w-full text-left px-3 py-2 text-[12px] text-fg hover:bg-bg-muted"
            >
              <div className="font-medium">{u.displayName ?? u.username}</div>
              <div className="text-[10px] text-fg-muted">
                @{u.username} · {u.publicId.slice(0, 8)}…
              </div>
            </button>
          ))}
        </div>
      )}
    </div>
  );
}
