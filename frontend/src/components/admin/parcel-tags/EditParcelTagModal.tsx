"use client";
import { useEffect, useMemo, useState } from "react";
import { Dialog, DialogPanel, DialogTitle } from "@headlessui/react";
import { Button } from "@/components/ui/Button";
import { Input } from "@/components/ui/Input";
import { FormError } from "@/components/ui/FormError";
import { ApiError, isApiError } from "@/lib/api";
import { useUpdateParcelTag } from "@/hooks/admin/useAdminParcelTags";
import type { AdminParcelTagDto } from "@/lib/admin/parcelTags";

export interface EditParcelTagModalProps {
  open: boolean;
  onClose: () => void;
  tag: AdminParcelTagDto | null;
  existingCategories: string[];
}

/**
 * Edits everything except the {@code code} (which is admin-immutable). Only
 * fields that actually changed get sent in the PATCH body.
 */
export function EditParcelTagModal({
  open,
  onClose,
  tag,
  existingCategories,
}: EditParcelTagModalProps) {
  const [label, setLabel] = useState("");
  const [category, setCategory] = useState("");
  const [description, setDescription] = useState("");
  const [sortOrder, setSortOrder] = useState("");
  const [error, setError] = useState<string | null>(null);

  const mutation = useUpdateParcelTag();

  useEffect(() => {
    if (open && tag) {
      // eslint-disable-next-line react-hooks/set-state-in-effect -- `tag` is external source of truth; pre-filling form state on open is intentional (matches CreateBanModal pattern)
      setLabel(tag.label);
      setCategory(tag.category);
      setDescription(tag.description ?? "");
      setSortOrder(String(tag.sortOrder));
      setError(null);
    }
  }, [open, tag]);

  const sortedCategories = useMemo(
    () => [...new Set(existingCategories)].sort((a, b) => a.localeCompare(b)),
    [existingCategories],
  );

  async function submit() {
    if (!tag) return;
    setError(null);
    const body: Record<string, unknown> = {};
    if (label.trim() !== tag.label) body.label = label.trim();
    if (category.trim() !== tag.category) body.category = category.trim();
    if ((description.trim() || "") !== (tag.description ?? ""))
      body.description = description.trim();
    const newSortOrder = sortOrder.trim() ? Number(sortOrder) : tag.sortOrder;
    if (newSortOrder !== tag.sortOrder) body.sortOrder = newSortOrder;

    if (Object.keys(body).length === 0) {
      onClose();
      return;
    }
    try {
      await mutation.mutateAsync({ code: tag.code, body });
      onClose();
    } catch (e) {
      if (e instanceof ApiError || isApiError(e)) {
        setError(
          e.problem.detail ?? e.problem.title ?? "Could not save tag.",
        );
        return;
      }
      setError(e instanceof Error ? e.message : "Could not save tag.");
    }
  }

  return (
    <Dialog
      open={open}
      onClose={() => (mutation.isPending ? null : onClose())}
      className="relative z-50"
    >
      <div
        className="fixed inset-0 bg-inverse-surface/40 backdrop-blur-sm"
        aria-hidden="true"
      />
      <div className="fixed inset-0 flex items-center justify-center p-4">
        <DialogPanel
          data-testid="edit-parcel-tag-modal"
          className="w-full max-w-md flex flex-col gap-4 rounded-lg bg-bg-subtle p-6"
        >
          <DialogTitle className="text-base font-bold tracking-tight text-fg">
            Edit parcel tag
          </DialogTitle>

          <div className="rounded-lg bg-surface-raised px-3 py-2">
            <span className="text-[11px] uppercase font-medium text-fg-muted">
              Code (locked)
            </span>
            <p className="font-mono text-sm text-fg" data-testid="edit-parcel-tag-code">
              {tag?.code ?? ""}
            </p>
          </div>

          <Field label="Label">
            <Input
              type="text"
              value={label}
              onChange={(e) => setLabel(e.target.value)}
              maxLength={100}
              data-testid="edit-parcel-tag-label"
            />
          </Field>

          <Field label="Category">
            <Input
              type="text"
              value={category}
              onChange={(e) => setCategory(e.target.value)}
              maxLength={50}
              list="parcel-tag-categories-edit"
              data-testid="edit-parcel-tag-category"
            />
            <datalist id="parcel-tag-categories-edit">
              {sortedCategories.map((c) => (
                <option key={c} value={c} />
              ))}
            </datalist>
          </Field>

          <Field label="Description">
            <textarea
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              rows={3}
              maxLength={2000}
              className="w-full resize-y rounded-lg bg-bg-subtle px-4 py-3 text-fg ring-1 ring-border-subtle focus:outline-none focus-visible:ring-2 focus-visible:ring-brand"
              data-testid="edit-parcel-tag-description"
            />
          </Field>

          <Field label="Sort order">
            <Input
              type="number"
              value={sortOrder}
              onChange={(e) => setSortOrder(e.target.value)}
              data-testid="edit-parcel-tag-sort-order"
            />
          </Field>

          <FormError message={error ?? undefined} />

          <div className="flex justify-end gap-2">
            <Button
              variant="secondary"
              onClick={onClose}
              disabled={mutation.isPending}
            >
              Cancel
            </Button>
            <Button
              variant="primary"
              onClick={submit}
              disabled={mutation.isPending || !label.trim() || !category.trim()}
              loading={mutation.isPending}
              data-testid="edit-parcel-tag-submit"
            >
              Save changes
            </Button>
          </div>
        </DialogPanel>
      </div>
    </Dialog>
  );
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <label className="flex flex-col gap-1">
      <span className="text-xs font-medium text-fg-muted">{label}</span>
      {children}
    </label>
  );
}
