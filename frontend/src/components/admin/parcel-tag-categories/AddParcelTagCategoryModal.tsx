"use client";
import { useEffect, useState } from "react";
import { Dialog, DialogPanel, DialogTitle } from "@headlessui/react";
import { Button } from "@/components/ui/Button";
import { Input } from "@/components/ui/Input";
import { FormError } from "@/components/ui/FormError";
import { ApiError, isApiError } from "@/lib/api";
import { useCreateParcelTagCategory } from "@/hooks/admin/useAdminParcelTagCategories";

export interface AddParcelTagCategoryModalProps {
  open: boolean;
  onClose: () => void;
}

export function AddParcelTagCategoryModal({
  open,
  onClose,
}: AddParcelTagCategoryModalProps) {
  const [code, setCode] = useState("");
  const [label, setLabel] = useState("");
  const [description, setDescription] = useState("");
  const [error, setError] = useState<string | null>(null);

  const mutation = useCreateParcelTagCategory();

  useEffect(() => {
    if (!open) {
      // eslint-disable-next-line react-hooks/set-state-in-effect -- `open` is external source of truth; resetting on close mirrors AddParcelTagModal.
      setCode("");
      setLabel("");
      setDescription("");
      setError(null);
    }
  }, [open]);

  async function submit() {
    setError(null);
    try {
      await mutation.mutateAsync({
        code: code.trim(),
        label: label.trim(),
        description: description.trim() || undefined,
      });
      onClose();
    } catch (e) {
      if (e instanceof ApiError || isApiError(e)) {
        setError(
          e.problem.detail ?? e.problem.title ?? "Could not create category.",
        );
        return;
      }
      setError(e instanceof Error ? e.message : "Could not create category.");
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
          data-testid="add-parcel-tag-category-modal"
          className="w-full max-w-md flex flex-col gap-4 rounded-lg bg-bg-subtle p-6"
        >
          <DialogTitle className="text-base font-bold tracking-tight text-fg">
            Add parcel category
          </DialogTitle>

          <Field
            label="Code"
            hint="Uppercase letters, digits, underscore. Cannot be changed later."
          >
            <Input
              type="text"
              value={code}
              onChange={(e) =>
                setCode(e.target.value.toUpperCase().replace(/[^A-Z0-9_]/g, ""))
              }
              maxLength={50}
              placeholder="TERRAIN"
              data-testid="add-parcel-tag-category-code"
            />
          </Field>

          <Field label="Label">
            <Input
              type="text"
              value={label}
              onChange={(e) => setLabel(e.target.value)}
              maxLength={100}
              placeholder="Terrain"
              data-testid="add-parcel-tag-category-label"
            />
          </Field>

          <Field label="Description (optional)">
            <textarea
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              rows={3}
              maxLength={2000}
              className="w-full resize-y rounded-lg bg-bg-subtle px-4 py-3 text-fg ring-1 ring-border-subtle focus:outline-none focus-visible:ring-2 focus-visible:ring-brand"
              data-testid="add-parcel-tag-category-description"
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
              disabled={mutation.isPending || !code.trim() || !label.trim()}
              loading={mutation.isPending}
              data-testid="add-parcel-tag-category-submit"
            >
              Add category
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
