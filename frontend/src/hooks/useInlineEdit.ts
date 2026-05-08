"use client";
import { useState, useCallback } from "react";

export type InlineEditState = "idle" | "editing" | "saving";

export interface UseInlineEditArgs<T> {
  initialValue: T;
  onSave: (value: T) => Promise<void>;
  isEqual?: (a: T, b: T) => boolean;
}

export interface UseInlineEditReturn<T> {
  state: InlineEditState;
  draft: T;
  error: string | null;
  startEdit: () => void;
  setDraft: (next: T) => void;
  commit: () => Promise<void>;
  cancel: () => void;
}

/**
 * Tiny state machine for click-to-edit fields.
 *
 * idle -> startEdit -> editing -> commit -> saving -> idle (success)
 *                                        -> editing (failure, error set)
 *                  -> cancel -> idle (draft discarded)
 *
 * commit() is a no-op when draft equals initialValue (saves a round-trip
 * when the seller opens an editor and clicks away without changing the
 * value).
 */
export function useInlineEdit<T>(args: UseInlineEditArgs<T>): UseInlineEditReturn<T> {
  const isEqual = args.isEqual ?? ((a: T, b: T) => Object.is(a, b));
  const [state, setState] = useState<InlineEditState>("idle");
  const [draft, setDraftState] = useState<T>(args.initialValue);
  const [error, setError] = useState<string | null>(null);

  const startEdit = useCallback(() => {
    setDraftState(args.initialValue);
    setError(null);
    setState("editing");
  }, [args.initialValue]);

  const setDraft = useCallback((next: T) => {
    setDraftState(next);
  }, []);

  const cancel = useCallback(() => {
    setError(null);
    setState("idle");
  }, []);

  const commit = useCallback(async () => {
    if (state !== "editing") return;
    if (isEqual(draft, args.initialValue)) {
      setState("idle");
      return;
    }
    setState("saving");
    try {
      await args.onSave(draft);
      setState("idle");
      setError(null);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Save failed");
      setState("editing");
      throw e;
    }
  }, [state, draft, args, isEqual]);

  return { state, draft, error, startEdit, setDraft, commit, cancel };
}
