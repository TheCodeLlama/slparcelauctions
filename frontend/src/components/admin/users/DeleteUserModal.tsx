"use client";
import { useState } from "react";
import { useRouter } from "next/navigation";
import { isApiError } from "@/lib/api";
import { useDeleteUser } from "@/lib/admin/deletionHooks";

type PreconditionError = {
  code: string;
  message: string;
  blockingIds: number[];
};

type Props = { userId: number; userEmail?: string | null; onClose: () => void };

export function DeleteUserModal({ userId, userEmail, onClose }: Props) {
  const [adminNote, setAdminNote] = useState("");
  const [error, setError] = useState<string | PreconditionError | null>(null);
  const router = useRouter();
  const mutation = useDeleteUser();

  const submit = () => {
    setError(null);
    mutation.mutate(
      { userId, adminNote },
      {
        onSuccess: () => {
          onClose();
          router.push("/admin/users");
        },
        onError: (e: unknown) => {
          if (isApiError(e)) {
            if (e.status === 409 && typeof e.problem.blockingIds !== "undefined") {
              setError({
                code: e.problem.code as string,
                message: e.problem.message as string,
                blockingIds: e.problem.blockingIds as number[],
              });
            } else if (e.status === 410) {
              setError("User already deleted.");
            } else {
              setError("Failed to delete user. Please try again.");
            }
          } else {
            setError("Failed to delete user. Please try again.");
          }
        },
      }
    );
  };

  return (
    <div
      className="fixed inset-0 bg-black/60 z-50 flex items-center justify-center"
      onClick={onClose}
    >
      <div
        className="bg-surface rounded p-5 max-w-md w-full"
        onClick={(e) => e.stopPropagation()}
      >
        <h2 className="text-sm font-semibold mb-2">
          Delete user{userEmail ? ` ${userEmail}` : ""}?
        </h2>
        <p className="text-[11px] opacity-70 mb-3">
          This soft-deletes the user. Their identity is scrubbed but their auctions
          and reviews remain visible as &quot;Deleted user&quot;.
        </p>
        <label className="text-[10px] uppercase opacity-55 block mb-1">
          Admin note <span className="text-error normal-case">(required)</span>
        </label>
        <textarea
          value={adminNote}
          onChange={(e) => setAdminNote(e.target.value)}
          placeholder="Reason for deletion (e.g., 'GDPR request', 'Spam account')"
          maxLength={500}
          className="w-full h-20 bg-surface-container-low text-xs p-2 rounded resize-y"
        />
        <div className="text-[10px] opacity-40 mt-1 mb-3">{adminNote.length} / 500</div>

        {error && typeof error === "string" && (
          <p className="text-error text-xs mb-3">{error}</p>
        )}
        {error && typeof error !== "string" && (
          <div className="text-error text-xs mb-3">
            <p className="font-medium">Cannot delete: {error.code}</p>
            <p className="mt-1 opacity-85">{error.message}</p>
            {error.blockingIds.length > 0 && (
              <ul className="mt-2 space-y-1">
                {error.blockingIds.map((id) => (
                  <li key={id}>· #{id}</li>
                ))}
              </ul>
            )}
          </div>
        )}

        <div className="flex gap-2">
          <button
            type="button"
            onClick={onClose}
            className="flex-1 px-3 py-2 border border-outline rounded text-xs"
          >
            Cancel
          </button>
          <button
            type="button"
            disabled={!adminNote.trim() || mutation.isPending}
            onClick={submit}
            className="flex-1 px-3 py-2 bg-error text-on-error rounded text-xs font-semibold disabled:opacity-50"
          >
            {mutation.isPending ? "Deleting…" : "Delete user"}
          </button>
        </div>
      </div>
    </div>
  );
}
