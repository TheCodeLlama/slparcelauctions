"use client";

import {
  createContext,
  useCallback,
  useEffect,
  useRef,
  useState,
  type ReactNode,
} from "react";
import { createPortal } from "react-dom";
import { CheckCircle2, AlertCircle, AlertTriangle } from "@/components/ui/icons";
import { cn } from "@/lib/cn";

/**
 * Four variants. {@code success} and {@code error} predate Epic 07 and keep
 * their existing contract. Epic 07 sub-spec 2 adds {@code warning} (used by
 * the ListingCard's unauth "sign in to save" toast, among others) and
 * {@code info} for neutral, non-urgent notifications.
 */
export type ToastKind = "success" | "error" | "warning" | "info";

/**
 * Structured payload. When the caller needs a title/description split or an
 * action button (e.g. "Sign in" on the save-parcel auth gate), they pass
 * this shape instead of a plain string. Backward-compatible — plain strings
 * still work and simply set {@code title}.
 */
export type ToastPayload = {
  title: string;
  description?: string;
  action?: { label: string; onClick: () => void };
};

export type ToastItem = {
  id: string;
  kind: ToastKind;
  message: string;
  description?: string;
  action?: { label: string; onClick: () => void };
};

export type ToastContextValue = {
  toasts: ToastItem[];
  push: (kind: ToastKind, payload: string | ToastPayload) => void;
  dismiss: (id: string) => void;
  upsert: (id: string, kind: ToastKind, payload: string | ToastPayload) => void;
};

export const ToastContext = createContext<ToastContextValue | null>(null);

const MAX_VISIBLE = 3;
const AUTO_DISMISS_MS = 3000;

/**
 * Map a variant to its icon + container tone classes. Kept in one place so
 * the design-token surface is easy to audit. Each tone uses only CSS-var
 * backed tokens — no {@code dark:} variants, no raw hex.
 */
const KIND_CLASSES: Record<ToastKind, string> = {
  success: "bg-brand text-white",
  error: "bg-danger text-white",
  warning: "bg-warning-bg text-warning",
  info: "bg-bg-hover text-fg",
};

const KIND_ICON: Record<ToastKind, typeof CheckCircle2> = {
  success: CheckCircle2,
  error: AlertCircle,
  warning: AlertTriangle,
  info: AlertCircle,
};

/**
 * ARIA role by variant. {@code error} and {@code warning} use {@code alert}
 * for urgent surfacing; {@code success} and {@code info} use {@code status}
 * which announces politely without interrupting.
 */
function roleFor(kind: ToastKind): "alert" | "status" {
  return kind === "error" || kind === "warning" ? "alert" : "status";
}

export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<ToastItem[]>([]);
  const [mounted, setMounted] = useState(false);
  // Tracks auto-dismiss timers keyed by toast id so upsert can cancel and
  // reset the timer when a notification with the same id arrives again.
  const timersRef = useRef<Map<string, ReturnType<typeof setTimeout>>>(new Map());

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- hydration guard for createPortal; same pattern as ThemeToggle.tsx
    setMounted(true);
  }, []);

  const dismiss = useCallback((id: string) => {
    setToasts((prev) => prev.filter((t) => t.id !== id));
  }, []);

  const push = useCallback(
    (kind: ToastKind, payload: string | ToastPayload) => {
      const id =
        typeof crypto !== "undefined" && "randomUUID" in crypto
          ? crypto.randomUUID()
          : `${Date.now()}-${Math.random()}`;

      const { message, description, action } =
        typeof payload === "string"
          ? { message: payload, description: undefined, action: undefined }
          : {
              message: payload.title,
              description: payload.description,
              action: payload.action,
            };

      // Defer the state update so it does not interfere with React Query's
      // invalidation batching when push() is called from a mutation onSuccess
      // callback. A synchronous setToasts call would be merged by React 19
      // auto-batching with the mutation's internal state transition, preventing
      // query refetches from resolving before the next render.
      setTimeout(() => {
        setToasts((prev) => {
          const next = [...prev, { id, kind, message, description, action }];
          return next.length > MAX_VISIBLE ? next.slice(-MAX_VISIBLE) : next;
        });
      }, 0);

      timersRef.current.set(id, setTimeout(() => dismiss(id), AUTO_DISMISS_MS));
    },
    [dismiss],
  );

  /**
   * Upsert a toast by a caller-supplied id. When a toast with the same id
   * already exists it is replaced in place and its auto-dismiss timer is
   * reset — collapsing rapid notification updates (e.g. OUTBID storm) into
   * a single visible entry that extends its lifetime on each update.
   */
  const upsert = useCallback(
    (id: string, kind: ToastKind, payload: string | ToastPayload) => {
      const next: ToastItem = {
        id,
        kind,
        message: typeof payload === "string" ? payload : payload.title,
        description: typeof payload === "string" ? undefined : (payload as ToastPayload).description,
        action: typeof payload === "string" ? undefined : (payload as ToastPayload).action,
      };
      setToasts((prev) => {
        const existingIdx = prev.findIndex((t) => t.id === id);
        if (existingIdx >= 0) {
          // Replace in place so the toast keeps its queue position.
          const updated = [...prev];
          updated[existingIdx] = next;
          return updated;
        }
        // Fresh id — apply MAX_VISIBLE cap.
        const trimmed = prev.length >= MAX_VISIBLE ? prev.slice(prev.length - MAX_VISIBLE + 1) : prev;
        return [...trimmed, next];
      });
      // Cancel any running timer and start a fresh one.
      const existing = timersRef.current.get(id);
      if (existing !== undefined) clearTimeout(existing);
      timersRef.current.set(id, setTimeout(() => dismiss(id), AUTO_DISMISS_MS));
    },
    [dismiss],
  );

  return (
    <ToastContext.Provider value={{ toasts, push, dismiss, upsert }}>
      {children}
      {mounted &&
        createPortal(
          <div
            className="fixed top-4 right-4 z-50 flex flex-col gap-2 pointer-events-none"
            data-testid="toast-stack"
          >
            {toasts.map((toast) => {
              const Icon = KIND_ICON[toast.kind];
              return (
                <div
                  key={toast.id}
                  role={roleFor(toast.kind)}
                  className={cn(
                    "pointer-events-auto flex items-start gap-3 px-4 py-3 rounded-lg shadow-lg animate-slide-in-from-top",
                    KIND_CLASSES[toast.kind],
                  )}
                >
                  <Icon
                    className="size-5 mt-0.5 shrink-0"
                    aria-hidden="true"
                  />
                  <div className="flex flex-col gap-1 min-w-0">
                    <span className="text-sm font-medium">
                      {toast.message}
                    </span>
                    {toast.description && (
                      <span className="text-xs opacity-90">
                        {toast.description}
                      </span>
                    )}
                    {toast.action && (
                      <button
                        type="button"
                        onClick={() => {
                          toast.action?.onClick();
                          dismiss(toast.id);
                        }}
                        className="self-start text-xs font-semibold underline underline-offset-2 hover:no-underline focus-visible:ring-2 focus-visible:ring-current rounded"
                      >
                        {toast.action.label}
                      </button>
                    )}
                  </div>
                </div>
              );
            })}
          </div>,
          document.body,
        )}
    </ToastContext.Provider>
  );
}
