// export/realty-groups/components/Pagination.tsx
"use client";

import { ChevronLeft, ChevronRight } from "lucide-react";
import { Btn } from "./Btn";
import { cn } from "../lib/cn";

interface PaginationProps {
  page: number;
  totalPages: number;
  onChange: (page: number) => void;
}

export function Pagination({ page, totalPages, onChange }: PaginationProps) {
  if (totalPages <= 1) return null;

  return (
    <div className="flex justify-between items-center mt-6">
      <div className="text-sm text-fg-muted">
        Page {page + 1} of {totalPages}
      </div>
      <div className="flex gap-1.5 items-center">
        <Btn
          variant="secondary"
          size="sm"
          onClick={() => onChange(Math.max(0, page - 1))}
          disabled={page === 0}
        >
          <ChevronLeft className="w-3.5 h-3.5" /> Prev
        </Btn>
        {Array.from({ length: totalPages }, (_, i) => (
          <button
            key={i}
            type="button"
            onClick={() => onChange(i)}
            className={cn(
              "w-8 h-8 rounded-md text-xs font-semibold cursor-pointer border",
              page === i
                ? "border-brand bg-brand text-on-brand"
                : "border-border bg-transparent text-fg-muted hover:bg-bg-muted",
            )}
          >
            {i + 1}
          </button>
        ))}
        <Btn
          variant="secondary"
          size="sm"
          onClick={() => onChange(Math.min(totalPages - 1, page + 1))}
          disabled={page === totalPages - 1}
        >
          Next <ChevronRight className="w-3.5 h-3.5" />
        </Btn>
      </div>
    </div>
  );
}
