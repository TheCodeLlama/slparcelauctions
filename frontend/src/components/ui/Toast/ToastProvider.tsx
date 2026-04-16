"use client";

import {
  createContext,
  useCallback,
  useEffect,
  useState,
  type ReactNode,
} from "react";
import { createPortal } from "react-dom";
import { CheckCircle2, AlertCircle } from "@/components/ui/icons";
import { cn } from "@/lib/cn";

export type ToastKind = "success" | "error";

export type ToastItem = { id: string; kind: ToastKind; message: string };

export type ToastContextValue = {
  toasts: ToastItem[];
  push: (kind: ToastKind, message: string) => void;
  dismiss: (id: string) => void;
};

export const ToastContext = createContext<ToastContextValue | null>(null);

const MAX_VISIBLE = 3;
const AUTO_DISMISS_MS = 3000;

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
    (kind: ToastKind, message: string) => {
      const id =
        typeof crypto !== "undefined" && "randomUUID" in crypto
          ? crypto.randomUUID()
          : `${Date.now()}-${Math.random()}`;

      // Defer the state update so it does not interfere with React Query's
      // invalidation batching when push() is called from a mutation onSuccess
      // callback. A synchronous setToasts call would be merged by React 19
      // auto-batching with the mutation's internal state transition, preventing
      // query refetches from resolving before the next render.
      setTimeout(() => {
        setToasts((prev) => {
          const next = [...prev, { id, kind, message }];
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
              const Icon =
                toast.kind === "success" ? CheckCircle2 : AlertCircle;
              return (
                <div
                  key={toast.id}
                  role={toast.kind === "error" ? "alert" : "status"}
                  className={cn(
                    "pointer-events-auto flex items-center gap-3 px-4 py-3 rounded-lg shadow-lg animate-slide-in-from-top",
                    toast.kind === "success"
                      ? "bg-primary text-on-primary"
                      : "bg-error text-on-error",
                  )}
                >
                  <Icon className="size-5" aria-hidden="true" />
                  <span className="text-body-md font-medium">
                    {toast.message}
                  </span>
                </div>
              );
            })}
          </div>,
          document.body,
        )}
    </ToastContext.Provider>
  );
}
