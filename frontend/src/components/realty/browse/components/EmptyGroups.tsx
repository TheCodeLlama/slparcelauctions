"use client";

import { Users } from "lucide-react";
import { Btn } from "./Btn";

interface EmptyGroupsProps {
  query: string;
  onClear: () => void;
}

export function EmptyGroups({ query, onClear }: EmptyGroupsProps) {
  return (
    <div className="rounded-lg border border-border bg-surface-raised p-12 text-center">
      <div className="w-14 h-14 mx-auto mb-3 rounded-full bg-bg-muted text-fg-subtle grid place-items-center">
        <Users className="w-6 h-6" />
      </div>
      <div className="text-[15px] font-semibold text-fg">
        {query ? `No groups match \u201c${query}\u201d` : "No groups match these filters"}
      </div>
      <div className="text-xs text-fg-muted mt-1">
        Try a different keyword or clear filters.
      </div>
      <Btn variant="secondary" onClick={onClear} className="mt-3.5">
        Clear filters
      </Btn>
    </div>
  );
}
