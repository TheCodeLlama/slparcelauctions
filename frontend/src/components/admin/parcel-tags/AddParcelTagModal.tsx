"use client";
import { useEffect, useMemo, useState } from "react";
import { Dialog, DialogPanel, DialogTitle } from "@headlessui/react";
import { Button } from "@/components/ui/Button";
import { Input } from "@/components/ui/Input";
import { FormError } from "@/components/ui/FormError";
import { ApiError, isApiError } from "@/lib/api";
import { useCreateParcelTag } from "@/hooks/admin/useAdminParcelTags";
import type { AdminParcelTagDto } from "@/lib/admin/parcelTags";

export interface AddParcelTagModalProps {
  open: boolean;
  onClose: () => void;
  /** Distinct categories from the existing tag list — feeds the datalist autocomplete. */
  existingCategories: string[];
}

export function AddParcelTagModal({
  open,
  onClose,
  existingCategories,
}: AddParcelTagModalProps) {
  const [code, setCode] = useState("");
  const [label, setLabel] = useState("");
  const [category, setCategory] = useState("");
  const [description, setDescription] = useState("");
  const [sortOrder, setSortOrder] = useState("");
  const [error, setError] = useState<string | null>(null);

  const mutation = useCreateParcelTag();

  useEffect(() => {
    if (!open) {
      setCode("");
      setLabel("");
      setCategory("");
      setDescription("");
      setSortOrder("");
      setError(null);
    }
  }, [open]);

  const sortedCategories = useMemo(
    () => [...new Set(existingCategories)].sort((a, b) => a.localeCompare(b)),
    [existingCategories],
  );

  async function submit() {
    setError(null);
    try {
      await mutation.mutateAsync({
        code: code.trim(),
        label: label.trim(),
        category: category.trim(),
        description: description.trim() || undefined,
        sortOrder: sortOrder.trim() ? Number(sortOrder) : undefined,
      });
      onClose();
    } catch (e) {
      if (e instanceof ApiError || isApiError(e)) {
        setError(
          e.problem.detail ?? e.problem.title ?? "Could not create tag.",
        );
        return;
      }
      setError(e instanceof Error ? e.message : "Could not create tag.");
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
          data-testid="add-parcel-tag-modal"
          className="w-full max-w-md flex flex-col gap-4 rounded-lg bg-bg-subtle p-6"
        >
          <DialogTitle className="text-base font-bold tracking-tight text-fg">
            Add parcel tag
          </DialogTitle>

          <Field label="Code" hint="Uppercase letters, digits, underscore. Cannot be changed later.">
            <Input
              type="text"
              value={code}
              onChange={(e) =>
                setCode(e.target.value.toUpperCase().replace(/[^A-Z0-9_]/g, ""))
              }
              maxLength={50}
              placeholder="BEACHFRONT"
              data-testid="add-parcel-tag-code"
            />
          </Field>

          <Field label="Label">
            <Input
              type="text"
              value={label}
              onChange={(e) => setLabel(e.target.value)}
              maxLength={100}
              placeholder="Beachfront"
              data-testid="add-parcel-tag-label"
            />
          </Field>

          <Field label="Category" hint="Type a new category or pick an existing one.">
            <Input
              type="text"
              value={category}
              onChange={(e) => setCategory(e.target.value)}
              maxLength={50}
              list="parcel-tag-categories"
              placeholder="Terrain / Environment"
              data-testid="add-parcel-tag-category"
            />
            <datalist id="parcel-tag-categories">
              {sortedCategories.map((c) => (
                <option key={c} value={c} />
              ))}
            </datalist>
          </Field>

          <Field label="Description (optional)">
            <textarea
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              rows={3}
              maxLength={2000}
              className="w-full resize-y rounded-lg bg-bg-subtle px-4 py-3 text-fg ring-1 ring-border-subtle focus:outline-none focus-visible:ring-2 focus-visible:ring-brand"
              data-testid="add-parcel-tag-description"
            />
          </Field>

          <Field
            label="Sort order (optional)"
            hint="Defaults to one after the last tag in the chosen category."
          >
            <Input
              type="number"
              value={sortOrder}
              onChange={(e) => setSortOrder(e.target.value)}
              data-testid="add-parcel-tag-sort-order"
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
              disabled={
                mutation.isPending ||
                !code.trim() ||
                !label.trim() ||
                !category.trim()
              }
              loading={mutation.isPending}
              data-testid="add-parcel-tag-submit"
            >
              Add tag
            </Button>
          </div>
        </DialogPanel>
      </div>
    </Dialog>
  );
}

function Field({
  label,
  hint,
  children,
}: {
  label: string;
  hint?: string;
  children: React.ReactNode;
}) {
  return (
    <label className="flex flex-col gap-1">
      <span className="text-xs font-medium text-fg-muted">{label}</span>
      {children}
      {hint && <span className="text-[11px] text-fg-muted">{hint}</span>}
    </label>
  );
}
