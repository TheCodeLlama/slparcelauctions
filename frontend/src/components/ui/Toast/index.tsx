// STUB: real implementation lands in Task 3.
// This exists so that lib/user/hooks.ts can import { useToast } at Task 1 time.
import type { ReactNode } from "react";

export function useToast() {
  return {
    success: (_message: string) => {},
    error: (_message: string) => {},
  };
}

export function ToastProvider({ children }: { children: ReactNode }) {
  return <>{children}</>;
}
