"use client";
import { useMemo } from "react";
import { Button } from "@/components/ui/Button";
import { useToast } from "@/components/ui/Toast";
import { isApiError } from "@/lib/api";
import { useToggleParcelTagActive } from "@/hooks/admin/useAdminParcelTags";
import type { AdminParcelTagDto } from "@/lib/admin/parcelTags";
import { cn } from "@/lib/cn";

export interface AdminParcelTagsTableProps {
  tags: AdminParcelTagDto[];
  onEdit: (tag: AdminParcelTagDto) => void;
}

interface CategoryGroup {
  category: string;
  tags: AdminParcelTagDto[];
}

function groupByCategory(tags: AdminParcelTagDto[]): CategoryGroup[] {
  const order: string[] = [];
  const map = new Map<string, AdminParcelTagDto[]>();
  for (const t of tags) {
    if (!map.has(t.category)) {
      map.set(t.category, []);
      order.push(t.category);
    }
    map.get(t.category)!.push(t);
  }
  return order.map((category) => ({
    category,
    tags: map.get(category)!.sort((a, b) => a.sortOrder - b.sortOrder),
  }));
}

export function AdminParcelTagsTable({ tags, onEdit }: AdminParcelTagsTableProps) {
  const groups = useMemo(() => groupByCategory(tags), [tags]);
  const toggle = useToggleParcelTagActive();
  const toast = useToast();

  async function onToggle(tag: AdminParcelTagDto) {
    try {
      await toggle.mutateAsync(tag.code);
      toast.success(
        tag.active
          ? `Tag ${tag.code} disabled.`
          : `Tag ${tag.code} re-enabled.`,
      );
    } catch (e) {
      const detail = isApiError(e)
        ? e.problem.detail ?? e.problem.title ?? "Could not toggle tag."
        : e instanceof Error
          ? e.message
          : "Could not toggle tag.";
      toast.error(detail);
    }
  }

  if (tags.length === 0) {
    return (
      <p
        className="rounded-lg bg-surface-raised px-4 py-6 text-sm text-fg-muted"
        data-testid="admin-parcel-tags-empty"
      >
        No parcel tags yet. Add one with the button above.
      </p>
    );
  }

  return (
    <div className="flex flex-col gap-6" data-testid="admin-parcel-tags-table">
      {groups.map((group) => (
        <section
          key={group.category}
          aria-label={`Tags in ${group.category}`}
          data-testid={`admin-parcel-tags-group-${group.category}`}
        >
          <h2 className="mb-2 text-sm font-bold tracking-tight text-fg">
            {group.category}
          </h2>
          <div className="overflow-hidden rounded-lg ring-1 ring-border-subtle">
            <table className="w-full text-sm">
              <thead className="bg-bg-muted text-[11px] uppercase text-fg-muted">
                <tr>
                  <th className="px-3 py-2 text-left">Code</th>
                  <th className="px-3 py-2 text-left">Label</th>
                  <th className="px-3 py-2 text-left">Sort</th>
                  <th className="px-3 py-2 text-left">Active</th>
                  <th className="px-3 py-2 text-right">Actions</th>
                </tr>
              </thead>
              <tbody>
                {group.tags.map((tag) => (
                  <tr
                    key={tag.code}
                    data-testid={`admin-parcel-tag-row-${tag.code}`}
                    data-active={tag.active}
                    className={cn(
                      "border-t border-border-subtle",
                      !tag.active && "opacity-60",
                    )}
                  >
                    <td className="px-3 py-2 font-mono text-xs text-fg">
                      {tag.code}
                    </td>
                    <td className="px-3 py-2 text-fg">{tag.label}</td>
                    <td className="px-3 py-2 text-fg-muted">{tag.sortOrder}</td>
                    <td className="px-3 py-2">
                      <span
                        className={cn(
                          "rounded-full px-2 py-0.5 text-[11px] font-medium",
                          tag.active
                            ? "bg-info-bg text-info"
                            : "bg-bg-muted text-fg-muted",
                        )}
                      >
                        {tag.active ? "Active" : "Inactive"}
                      </span>
                    </td>
                    <td className="px-3 py-2 text-right">
                      <div className="inline-flex gap-2">
                        <Button
                          variant="secondary"
                          size="sm"
                          onClick={() => onEdit(tag)}
                          data-testid={`admin-parcel-tag-edit-${tag.code}`}
                        >
                          Edit
                        </Button>
                        <Button
                          variant="secondary"
                          size="sm"
                          onClick={() => onToggle(tag)}
                          disabled={toggle.isPending}
                          data-testid={`admin-parcel-tag-toggle-${tag.code}`}
                        >
                          {tag.active ? "Disable" : "Re-enable"}
                        </Button>
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>
      ))}
    </div>
  );
}
