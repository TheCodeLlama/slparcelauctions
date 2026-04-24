"use client";

import {
  createContext,
  useCallback,
  useEffect,
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
  success: "bg-primary text-on-primary",
  error: "bg-error text-on-error",
  warning: "bg-error-container text-on-error-container",
  info: "bg-surface-container-high text-on-surface",
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

      setTimeout(() => dismiss(id), AUTO_DISMISS_MS);
    },
    [dismiss],
  );

  return (
    <ToastContext.Provider value={{ toasts, push, dismiss }}>
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
                    <span className="text-body-md font-medium">
                      {toast.message}
                    </span>
                    {toast.description && (
                      <span className="text-body-sm opacity-90">
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
                        className="self-start text-label-md font-semibold underline underline-offset-2 hover:no-underline focus-visible:outline focus-visible:outline-2 focus-visible:outline-current rounded"
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
