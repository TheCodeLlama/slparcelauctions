import { ChevronLeft, ChevronRight } from "@/components/ui/icons";
import { cn } from "@/lib/cn";

export interface PaginationProps {
  /** 0-indexed current page. */
  page: number;
  totalPages: number;
  onPageChange: (page: number) => void;
  className?: string;
}

/**
 * Produce the displayable page sequence for the bar.
 *
 * <= 7 pages: all pages are shown (1 2 3 4 5 6 7).
 * Otherwise, show the first page, up to two pages around the current, and
 * the last page, separated by ellipses. Collapses to patterns like:
 *   1 … 5 6 7 … 50
 */
function getPages(
  current: number,
  total: number,
): Array<number | "ellipsis"> {
  if (total <= 7) return Array.from({ length: total }, (_, i) => i);
  const pages: Array<number | "ellipsis"> = [0];
  if (current > 2) pages.push("ellipsis");
  for (
    let i = Math.max(1, current - 1);
    i <= Math.min(total - 2, current + 1);
    i++
  ) {
    pages.push(i);
  }
  if (current < total - 3) pages.push("ellipsis");
  pages.push(total - 1);
  return pages;
}

/**
 * Numbered pagination bar with prev/next. Returns {@code null} when
 * {@code totalPages <= 1} — the browse grid handles single-page lists by
 * omitting the bar entirely rather than rendering a disabled control.
 *
 * Page numbers are 0-indexed on the wire but rendered 1-indexed on the UI
 * (so users see "1 2 3 …" and page 1 ↔ 0 internally).
 */
export function Pagination({
  page,
  totalPages,
  onPageChange,
  className,
}: PaginationProps) {
  if (totalPages <= 1) return null;
  const pages = getPages(page, totalPages);
  return (
    <nav
      role="navigation"
      aria-label="Pagination"
      className={cn("flex items-center justify-center gap-1", className)}
    >
      <button
        type="button"
        onClick={() => onPageChange(page - 1)}
        disabled={page === 0}
        aria-label="Previous page"
        className="flex size-9 items-center justify-center rounded bg-surface-container-lowest text-on-surface disabled:opacity-40 hover:bg-surface-container-low"
      >
        <ChevronLeft className="size-4" aria-hidden="true" />
      </button>
      {pages.map((p, idx) =>
        p === "ellipsis" ? (
          <span
            key={`e-${idx}`}
            className="px-2 text-on-surface-variant"
            aria-hidden="true"
          >
            …
          </span>
        ) : (
          <button
            key={p}
            type="button"
            onClick={() => onPageChange(p)}
            aria-label={`Page ${p + 1}`}
            aria-current={p === page ? "page" : undefined}
            className={cn(
              "flex size-9 items-center justify-center rounded text-label-md",
              p === page
                ? "bg-primary text-on-primary"
                : "bg-surface-container-lowest text-on-surface hover:bg-surface-container-low",
            )}
          >
            {p + 1}
          </button>
        ),
      )}
      <button
        type="button"
        onClick={() => onPageChange(page + 1)}
        disabled={page === totalPages - 1}
        aria-label="Next page"
        className="flex size-9 items-center justify-center rounded bg-surface-container-lowest text-on-surface disabled:opacity-40 hover:bg-surface-container-low"
      >
        <ChevronRight className="size-4" aria-hidden="true" />
      </button>
    </nav>
  );
}
