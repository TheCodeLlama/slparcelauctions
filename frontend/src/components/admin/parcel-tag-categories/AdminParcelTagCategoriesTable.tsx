"use client";
import { Button } from "@/components/ui/Button";
import { useToast } from "@/components/ui/Toast";
import { isApiError } from "@/lib/api";
import { useToggleParcelTagCategoryActive } from "@/hooks/admin/useAdminParcelTagCategories";
import type { AdminParcelTagCategoryDto } from "@/lib/admin/parcelTagCategories";
import { cn } from "@/lib/cn";

export interface AdminParcelTagCategoriesTableProps {
  categories: AdminParcelTagCategoryDto[];
  onEdit: (cat: AdminParcelTagCategoryDto) => void;
}

export function AdminParcelTagCategoriesTable({
  categories,
  onEdit,
}: AdminParcelTagCategoriesTableProps) {
  const toggle = useToggleParcelTagCategoryActive();
  const toast = useToast();

  async function onToggle(cat: AdminParcelTagCategoryDto) {
    try {
      await toggle.mutateAsync(cat.code);
      toast.success(
        cat.active
          ? `Category ${cat.code} disabled.`
          : `Category ${cat.code} re-enabled.`,
      );
    } catch (e) {
      const detail = isApiError(e)
        ? e.problem.detail ?? e.problem.title ?? "Could not toggle category."
        : e instanceof Error
          ? e.message
          : "Could not toggle category.";
      toast.error(detail);
    }
  }

  if (categories.length === 0) {
    return (
      <p
        className="rounded-lg bg-surface-raised px-4 py-6 text-sm text-fg-muted"
        data-testid="admin-parcel-tag-categories-empty"
      >
        No categories yet. Add one with the button above.
      </p>
    );
  }

  return (
    <div
      className="overflow-hidden rounded-lg ring-1 ring-border-subtle"
      data-testid="admin-parcel-tag-categories-table"
    >
      <table className="w-full text-sm">
        <thead className="bg-bg-muted text-[11px] uppercase text-fg-muted">
          <tr>
            <th className="px-3 py-2 text-left">Code</th>
            <th className="px-3 py-2 text-left">Label</th>
            <th className="px-3 py-2 text-left">Active</th>
            <th className="px-3 py-2 text-right">Actions</th>
          </tr>
        </thead>
        <tbody>
          {categories.map((cat) => (
            <tr
              key={cat.code}
              data-testid={`admin-parcel-tag-category-row-${cat.code}`}
              data-active={cat.active}
              className={cn(
                "border-t border-border-subtle",
                !cat.active && "opacity-60",
              )}
            >
              <td className="px-3 py-2 font-mono text-xs text-fg">{cat.code}</td>
              <td className="px-3 py-2 text-fg">{cat.label}</td>
              <td className="px-3 py-2">
                <span
                  className={cn(
                    "rounded-full px-2 py-0.5 text-[11px] font-medium",
                    cat.active
                      ? "bg-info-bg text-info"
                      : "bg-bg-muted text-fg-muted",
                  )}
                >
                  {cat.active ? "Active" : "Inactive"}
                </span>
              </td>
              <td className="px-3 py-2 text-right">
                <div className="inline-flex gap-2">
                  <Button
                    variant="secondary"
                    size="sm"
                    onClick={() => onEdit(cat)}
                    data-testid={`admin-parcel-tag-category-edit-${cat.code}`}
                  >
                    Edit
                  </Button>
                  <Button
                    variant="secondary"
                    size="sm"
                    onClick={() => onToggle(cat)}
                    disabled={toggle.isPending}
                    data-testid={`admin-parcel-tag-category-toggle-${cat.code}`}
                  >
                    {cat.active ? "Disable" : "Re-enable"}
                  </Button>
                </div>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
