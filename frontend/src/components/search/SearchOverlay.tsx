"use client";

import { Combobox, Dialog, DialogPanel } from "@headlessui/react";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { useDebouncedValue } from "@/hooks/useDebouncedValue";
import { useMediaQuery } from "@/hooks/useMediaQuery";
import { useSearchSuggest } from "@/hooks/useSearchSuggest";
import { X } from "@/components/ui/icons";
import { cn } from "@/lib/cn";
import { SearchInput } from "./SearchInput";
import { SearchResultsList, type SearchSelection } from "./SearchResultsList";

export interface SearchOverlayProps {
  open: boolean;
  onClose: () => void;
}

const DESKTOP_QUERY = "(min-width: 768px)";

/**
 * Header search overlay. Same Combobox body in both layouts; the
 * wrapper toggles between an anchored panel (≥md) and a full-screen
 * Dialog (<md). Selection routing is one discriminated-union switch
 * — no branching per row component.
 */
export function SearchOverlay({ open, onClose }: SearchOverlayProps) {
  const router = useRouter();
  const [query, setQuery] = useState("");
  const debounced = useDebouncedValue(query, 250);
  const trimmed = debounced.trim();
  const queryState = useSearchSuggest(query);
  const isDesktop = useMediaQuery(DESKTOP_QUERY);

  function handleSelect(value: SearchSelection | null) {
    if (!value) return;
    if (value.kind === "listing") {
      router.push(`/auction/${value.id}`);
    } else if (value.kind === "region") {
      router.push(`/browse?region=${encodeURIComponent(value.name)}`);
    } else {
      router.push(`/browse?q=${encodeURIComponent(value.q)}`);
    }
    setQuery("");
    onClose();
  }

  if (!open) return null;

  const body = (
    <Combobox<SearchSelection | null>
      value={null}
      onChange={handleSelect}
      // immediate=true ensures clicks register without an intermediate
      // listbox highlight pass.
      immediate
    >
      <SearchInput value={query} onChange={setQuery} autoFocus />
      <SearchResultsList
        state={queryState}
        trimmed={trimmed}
        liveTrimmed={query.trim()}
      />
    </Combobox>
  );

  if (isDesktop) {
    return (
      <div
        className="fixed inset-0 z-[60]"
        onClick={onClose}
        onKeyDown={(e) => {
          if (e.key === "Escape") onClose();
        }}
        role="presentation"
      >
        <div
          className={cn(
            "absolute right-4 top-[var(--header-h)] mt-2 w-[480px]",
            "max-h-[520px] overflow-hidden",
            "rounded-lg border border-border bg-bg shadow-elevation-3",
          )}
          onClick={(e) => e.stopPropagation()}
          role="presentation"
        >
          {body}
        </div>
      </div>
    );
  }

  return (
    <Dialog open onClose={onClose} className="md:hidden relative z-[60]">
      <DialogPanel className="fixed inset-0 flex flex-col bg-bg">
        <div className="flex items-center gap-2 px-2 h-[var(--header-h)] border-b border-border bg-surface-raised">
          <button
            type="button"
            onClick={onClose}
            aria-label="Close search"
            className="grid h-9 w-9 place-items-center rounded-md hover:bg-bg-muted"
          >
            <X className="size-5 text-fg" />
          </button>
          <div className="flex-1">{body}</div>
        </div>
      </DialogPanel>
    </Dialog>
  );
}
