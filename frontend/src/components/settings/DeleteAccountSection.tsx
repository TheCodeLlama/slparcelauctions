"use client";
import { useState } from "react";
import { useRouter } from "next/navigation";
import { useQueryClient } from "@tanstack/react-query";
import { isApiError } from "@/lib/api";
import { setAccessToken } from "@/lib/auth/session";
import { useDeleteSelf } from "@/lib/admin/deletionHooks";

type PreconditionError = {
  code: string;
  message: string;
  blockingIds: number[];
};

export function DeleteAccountSection() {
  const [expanded, setExpanded] = useState(false);
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | PreconditionError | null>(null);
  const router = useRouter();
  const qc = useQueryClient();
  const mutation = useDeleteSelf();

  const submit = () => {
    setError(null);
    mutation.mutate(password, {
      onSuccess: () => {
        // Order matters: prevent 401 refresh race
        setAccessToken(null); // 1. nuke in-memory token first
        qc.clear();           // 2. drop all cached queries
        router.push("/goodbye"); // 3. then navigate
      },
      onError: (e: unknown) => {
        if (isApiError(e)) {
          const err = e;
          if (err.status === 409 && typeof err.problem.blockingIds !== "undefined") {
            setError({
              code: err.problem.code as string,
              message: err.problem.message as string,
              blockingIds: err.problem.blockingIds as number[],
            });
          } else if (err.status === 403) {
            setError("Incorrect password.");
          } else if (err.status === 410) {
            setError("Account already deleted.");
          } else {
            setError("Failed to delete account. Please try again.");
          }
        } else {
          setError("Failed to delete account. Please try again.");
        }
      },
    });
  };

  return (
    <section className="bg-error-container/20 border border-error rounded p-4 mt-8">
      <button
        type="button"
        onClick={() => setExpanded(!expanded)}
        className="w-full flex justify-between items-center text-left"
      >
        <span className="font-semibold text-error">Delete account</span>
        <span className="text-error">{expanded ? "▾" : "▸"}</span>
      </button>
      {expanded && (
        <div className="mt-4 space-y-3">
          <div className="bg-error-container text-on-error-container rounded p-3 text-xs">
            <strong>This is irreversible.</strong> Your auctions, bids, and reviews may remain
            visible as &quot;Deleted user&quot; to preserve past records. You can register a new
            account with the same email after deletion.
          </div>
          <input
            type="password"
            placeholder="Enter your password to confirm"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            className="w-full bg-surface-container-low p-2 rounded text-sm"
          />
          {error && typeof error === "string" && (
            <p className="text-error text-xs">{error}</p>
          )}
          {error && typeof error !== "string" && (
            <div className="text-error text-xs">
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
          <button
            type="button"
            disabled={!password || mutation.isPending}
            onClick={submit}
            className="px-4 py-2 bg-error text-on-error rounded text-sm font-semibold disabled:opacity-50"
          >
            {mutation.isPending ? "Deleting…" : "Delete my account"}
          </button>
        </div>
      )}
    </section>
  );
}
