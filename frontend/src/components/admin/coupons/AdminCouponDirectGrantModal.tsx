"use client";
import { useEffect, useState } from "react";
import { Trash2 } from "@/components/ui/icons";
import { Button } from "@/components/ui/Button";
import { UserSearchAutocomplete } from "@/components/admin/bans/UserSearchAutocomplete";
import { useDirectGrantCoupon } from "@/hooks/admin/useDirectGrantCoupon";
import { isApiError } from "@/lib/api";
import type { AdminUserSummary } from "@/lib/admin/types";

type Props = {
  open: boolean;
  couponPublicId: string;
  couponCode: string;
  onClose: () => void;
  /**
   * Called when the mutation succeeds. The argument is the count of
   * newly-created grants the server returned (may be less than the
   * caller's selection if some users were already at the max ceiling).
   */
  onSuccess?: (createdCount: number) => void;
};

function describeError(err: unknown): string {
  if (!isApiError(err)) return "Could not create grants. Try again.";
  const problem = err.problem as {
    code?: string;
    detail?: string;
    title?: string;
  };
  return problem.detail ?? problem.title ?? "Could not create grants.";
}

/**
 * Modal for admin direct-grant of a coupon to one or more users. Uses
 * the same `UserSearchAutocomplete` as the ban / create-form flows so
 * the admin can pick by name or email; multi-select is staged
 * client-side and submitted in a single POST.
 *
 * The server filters out users already at `maxPerUser` silently;
 * callers can observe the returned grant count via the
 * `onSuccess(createdCount)` callback.
 */
export function AdminCouponDirectGrantModal({
  open,
  couponPublicId,
  couponCode,
  onClose,
  onSuccess,
}: Props) {
  const [selected, setSelected] = useState<AdminUserSummary[]>([]);
  const mutation = useDirectGrantCoupon(couponPublicId);

  useEffect(() => {
    if (!open) {
      // eslint-disable-next-line react-hooks/set-state-in-effect
      setSelected([]);
      mutation.reset();
    }
    // mutation.reset is stable across renders; we only re-run when
    // `open` flips.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);

  useEffect(() => {
    function onKeyDown(e: KeyboardEvent) {
      if (e.key === "Escape" && open) onClose();
    }
    document.addEventListener("keydown", onKeyDown);
    return () => document.removeEventListener("keydown", onKeyDown);
  }, [open, onClose]);

  if (!open) return null;

  function handlePick(user: AdminUserSummary) {
    setSelected((prev) =>
      prev.some((u) => u.publicId === user.publicId) ? prev : [...prev, user],
    );
  }

  function handleRemove(publicId: string) {
    setSelected((prev) => prev.filter((u) => u.publicId !== publicId));
  }

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (selected.length === 0 || mutation.isPending) return;
    mutation.mutate(
      selected.map((u) => u.publicId),
      {
        onSuccess: (created) => {
          onSuccess?.(created.length);
          onClose();
        },
      },
    );
  }

  const error = mutation.isError ? describeError(mutation.error) : null;

  return (
    <>
      <div
        className="fixed inset-0 z-40 bg-scrim/40"
        onClick={onClose}
        aria-hidden="true"
      />
      <div
        role="dialog"
        aria-modal="true"
        aria-label={`Direct-grant coupon ${couponCode}`}
        data-testid="direct-grant-modal"
        className="fixed inset-0 z-50 flex items-center justify-center p-4"
      >
        <div
          className="w-full max-w-lg rounded-lg bg-surface-raised border border-border-subtle shadow-md p-6 flex flex-col gap-4"
          onClick={(e) => e.stopPropagation()}
        >
          <div className="flex items-start justify-between gap-2">
            <div>
              <h2 className="text-sm font-semibold text-fg">
                Direct-grant <span className="font-mono">{couponCode}</span>
              </h2>
              <p className="mt-1 text-xs text-fg-muted">
                Pick the users to receive this coupon. Anyone already at the
                per-user limit is skipped silently.
              </p>
            </div>
            <button
              type="button"
              onClick={onClose}
              aria-label="Close"
              className="p-1.5 rounded-lg text-fg-muted hover:bg-bg-muted"
              data-testid="direct-grant-close"
            >
              x
            </button>
          </div>

          <form onSubmit={handleSubmit} className="flex flex-col gap-4">
            <div className="flex flex-col gap-2">
              <label className="text-xs font-medium text-fg-muted">
                Recipients
              </label>
              <UserSearchAutocomplete
                onSelect={handlePick}
                placeholder="Search users to grant..."
              />
              {selected.length > 0 && (
                <div
                  className="mt-1 flex flex-wrap gap-1.5"
                  data-testid="direct-grant-selected"
                >
                  {selected.map((u) => (
                    <span
                      key={u.publicId}
                      className="inline-flex items-center gap-1.5 rounded-full bg-info-bg px-2.5 py-1 text-[11px] text-info"
                      data-testid={`direct-grant-chip-${u.publicId}`}
                    >
                      <span className="font-medium">
                        {u.displayName ?? u.username}
                      </span>
                      <button
                        type="button"
                        onClick={() => handleRemove(u.publicId)}
                        aria-label={`Remove ${u.displayName ?? u.username}`}
                        className="rounded-full p-0.5 hover:bg-info/20"
                      >
                        <Trash2 className="size-3" />
                      </button>
                    </span>
                  ))}
                </div>
              )}
            </div>

            {error && (
              <div
                role="alert"
                data-testid="direct-grant-error"
                className="rounded-lg border border-danger/30 bg-danger-bg/50 px-3 py-2 text-xs text-danger"
              >
                {error}
              </div>
            )}

            <div className="flex justify-end gap-2">
              <Button
                variant="secondary"
                type="button"
                onClick={onClose}
                disabled={mutation.isPending}
              >
                Cancel
              </Button>
              <Button
                variant="primary"
                type="submit"
                disabled={selected.length === 0 || mutation.isPending}
                loading={mutation.isPending}
                data-testid="direct-grant-submit"
              >
                Grant ({selected.length})
              </Button>
            </div>
          </form>
        </div>
      </div>
    </>
  );
}
