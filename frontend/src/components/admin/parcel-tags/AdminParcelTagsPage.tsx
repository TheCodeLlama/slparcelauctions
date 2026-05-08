"use client";
import { useMemo, useState } from "react";
import { Button } from "@/components/ui/Button";
import { LoadingSpinner } from "@/components/ui/LoadingSpinner";
import { FormError } from "@/components/ui/FormError";
import { useAdminParcelTags } from "@/hooks/admin/useAdminParcelTags";
import { isApiError } from "@/lib/api";
import type { AdminParcelTagDto } from "@/lib/admin/parcelTags";
import { AdminParcelTagsTable } from "./AdminParcelTagsTable";
import { AddParcelTagModal } from "./AddParcelTagModal";
import { EditParcelTagModal } from "./EditParcelTagModal";

export function AdminParcelTagsPage() {
  const { data: tags, isLoading, error } = useAdminParcelTags();
  const [addOpen, setAddOpen] = useState(false);
  const [editing, setEditing] = useState<AdminParcelTagDto | null>(null);

  const existingCategories = useMemo(
    () => Array.from(new Set((tags ?? []).map((t) => t.category))),
    [tags],
  );

  return (
    <div data-testid="admin-parcel-tags-page" className="flex flex-col gap-4">
      <header className="flex items-center justify-between">
        <div>
          <h1 className="text-lg font-bold tracking-tight text-fg">
            Parcel tags
          </h1>
          <p className="text-sm text-fg-muted">
            Add, edit, and disable the tags sellers can attach to listings.
          </p>
        </div>
        <Button
          variant="primary"
          onClick={() => setAddOpen(true)}
          data-testid="admin-parcel-tags-add"
        >
          + Add tag
        </Button>
      </header>

      {isLoading ? (
        <LoadingSpinner label="Loading tags…" />
      ) : error ? (
        <FormError
          message={
            isApiError(error)
              ? error.problem.detail ?? error.problem.title ?? "Could not load tags."
              : error instanceof Error
                ? error.message
                : "Could not load tags."
          }
        />
      ) : (
        <AdminParcelTagsTable
          tags={tags ?? []}
          onEdit={(tag) => setEditing(tag)}
        />
      )}

      <AddParcelTagModal
        open={addOpen}
        onClose={() => setAddOpen(false)}
        existingCategories={existingCategories}
      />
      <EditParcelTagModal
        open={editing != null}
        onClose={() => setEditing(null)}
        tag={editing}
        existingCategories={existingCategories}
      />
    </div>
  );
}
