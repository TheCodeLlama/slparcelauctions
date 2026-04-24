"use client";

import { useContext } from "react";
import { ToastContext, type ToastPayload } from "./ToastProvider";

/**
 * Fluent toast hook. Each variant accepts either a plain string (which
 * becomes the title) or a structured {@link ToastPayload} with optional
 * description + action button.
 *
 * Example:
 * <pre>
 *   toast.warning({
 *     title: "Sign in to save parcels",
 *     action: { label: "Sign in", onClick: () =&gt; router.push("/login") },
 *   });
 * </pre>
 */
export function useToast() {
  const ctx = useContext(ToastContext);
  if (!ctx) throw new Error("useToast must be used inside a ToastProvider");
  return {
    success: (payload: string | ToastPayload) => ctx.push("success", payload),
    error: (payload: string | ToastPayload) => ctx.push("error", payload),
    warning: (payload: string | ToastPayload) => ctx.push("warning", payload),
    info: (payload: string | ToastPayload) => ctx.push("info", payload),
  };
}
