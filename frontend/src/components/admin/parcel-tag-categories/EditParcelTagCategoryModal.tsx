"use client";
import { useEffect, useState } from "react";
import { Dialog, DialogPanel, DialogTitle } from "@headlessui/react";
import { Button } from "@/components/ui/Button";
import { Input } from "@/components/ui/Input";
import { FormError } from "@/components/ui/FormError";
import { ApiError, isApiError } from "@/lib/api";
import { useUpdateParcelTagCategory } from "@/hooks/admin/useAdminParcelTagCategories";
import type { AdminParcelTagCategoryDto } from "@/lib/admin/parcelTagCategories";

export interface EditParcelTagCategoryModalProps {
  open: boolean;
  onClose: () => void;
  category: AdminParcelTagCategoryDto | null;
}

export function EditParcelTagCategoryModal({
  open,
  onClose,
  category,
}: EditParcelTagCategoryModalProps) {
  const [label, setLabel] = useState("");
  const [description, setDescription] = useState("");
  const [error, setError] = useState<string | null>(null);

  const mutation = useUpdateParcelTagCategory();

  useEffect(() => {
    if (open && category) {
      // eslint-disable-next-line react-hooks/set-state-in-effect -- mirrors EditParcelTagModal pattern; `category` is external source of truth.
      setLabel(category.label);
      setDescription(category.description ?? "");
      setError(null);
    }
  }, [open, category]);

  async function submit() {
    if (!category) return;
    setError(null);
    const body: Record<string, unknown> = {};
    if (label.trim() !== category.label) body.label = label.trim();
    if ((description.trim() || "") !== (category.description ?? ""))
      body.description = description.trim();

    if (Object.keys(body).length === 0) {
      onClose();
      return;
    }
    try {
      await mutation.mutateAsync({ code: category.code, body });
      onClose();
    } catch (e) {
      if (e instanceof ApiError || isApiError(e)) {
        setError(
          e.problem.detail ?? e.problem.title ?? "Could not save category.",
        );
        return;
      }
      setError(e instanceof Error ? e.message : "Could not save category.");
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
          data-testid="edit-parcel-tag-category-modal"
          className="w-full max-w-md flex flex-col gap-4 rounded-lg bg-bg-subtle p-6"
        >
          <DialogTitle className="text-base font-bold tracking-tight text-fg">
            Edit parcel category
          </DialogTitle>

          <div className="rounded-lg bg-surface-raised px-3 py-2">
            <span className="text-[11px] uppercase font-medium text-fg-muted">
              Code (locked)
            </span>
            <p
              className="font-mono text-sm text-fg"
              data-testid="edit-parcel-tag-category-code"
            >
              {category?.code ?? ""}
            </p>
          </div>

          <Field label="Label">
            <Input
              type="text"
              value={label}
              onChange={(e) => setLabel(e.target.value)}
              maxLength={100}
              data-testid="edit-parcel-tag-category-label"
            />
          </Field>

          <Field label="Description">
            <textarea
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              rows={3}
              maxLength={2000}
              className="w-full resize-y rounded-lg bg-bg-subtle px-4 py-3 text-fg ring-1 ring-border-subtle focus:outline-none focus-visible:ring-2 focus-visible:ring-brand"
              data-testid="edit-parcel-tag-category-description"
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
              disabled={mutation.isPending || !label.trim()}
              loading={mutation.isPending}
              data-testid="edit-parcel-tag-category-submit"
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
