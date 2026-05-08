"use client";
import { useState } from "react";
import { Button } from "@/components/ui/Button";
import { LoadingSpinner } from "@/components/ui/LoadingSpinner";
import { FormError } from "@/components/ui/FormError";
import { useAdminParcelTagCategories } from "@/hooks/admin/useAdminParcelTagCategories";
import { isApiError } from "@/lib/api";
import type { AdminParcelTagCategoryDto } from "@/lib/admin/parcelTagCategories";
import { AdminParcelTagCategoriesTable } from "./AdminParcelTagCategoriesTable";
import { AddParcelTagCategoryModal } from "./AddParcelTagCategoryModal";
import { EditParcelTagCategoryModal } from "./EditParcelTagCategoryModal";

export function AdminParcelTagCategoriesPage() {
  const { data: categories, isLoading, error } = useAdminParcelTagCategories();
  const [addOpen, setAddOpen] = useState(false);
  const [editing, setEditing] = useState<AdminParcelTagCategoryDto | null>(null);

  return (
    <div data-testid="admin-parcel-tag-categories-page" className="flex flex-col gap-4">
      <header className="flex items-center justify-between">
        <div>
          <h1 className="text-lg font-bold tracking-tight text-fg">
            Parcel categories
          </h1>
          <p className="text-sm text-fg-muted">
            Group parcel tags. Disabling a category hides every tag in it from
            the public catalogue.
          </p>
        </div>
        <Button
          variant="primary"
          onClick={() => setAddOpen(true)}
          data-testid="admin-parcel-tag-categories-add"
        >
          + Add category
        </Button>
      </header>

      {isLoading ? (
        <LoadingSpinner label="Loading categories…" />
      ) : error ? (
        <FormError
          message={
            isApiError(error)
              ? error.problem.detail ?? error.problem.title ?? "Could not load categories."
              : error instanceof Error
                ? error.message
                : "Could not load categories."
          }
        />
      ) : (
        <AdminParcelTagCategoriesTable
          categories={categories ?? []}
          onEdit={(cat) => setEditing(cat)}
        />
      )}

      <AddParcelTagCategoryModal
        open={addOpen}
        onClose={() => setAddOpen(false)}
      />
      <EditParcelTagCategoryModal
        open={editing != null}
        onClose={() => setEditing(null)}
        category={editing}
      />
    </div>
  );
}
