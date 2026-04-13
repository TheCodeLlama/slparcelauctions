// frontend/src/components/auth/RequireAuth.tsx
"use client";

import { useEffect, type ReactNode } from "react";
import { useRouter, usePathname } from "next/navigation";
import { useAuth } from "@/lib/auth";

type RequireAuthProps = {
  children: ReactNode;
};

/**
 * Client-side guard for protected pages. Wraps children in a session check
 * and either renders them, redirects to /login, or shows a loading spinner.
 *
 * Three states from useAuth():
 *   - loading → centered spinner placeholder
 *   - unauthenticated → redirect via useEffect, render null in the meantime
 *   - authenticated → render children
 *
 * The redirect preserves the current pathname as a `next` query param so the
 * user lands back where they were after a successful login. The login form
 * uses `getSafeRedirect` to validate the param against open-redirect attacks.
 *
 * See spec §8 and FOOTGUNS §F.X (open-redirect guard).
 */
export function RequireAuth({ children }: RequireAuthProps) {
  const session = useAuth();
  const router = useRouter();
  const pathname = usePathname();

  useEffect(() => {
    if (session.status === "unauthenticated") {
      const next = encodeURIComponent(pathname);
      router.push(`/login?next=${next}`);
    }
  }, [session.status, pathname, router]);

  if (session.status === "loading") {
    return (
      <div className="flex min-h-[60vh] items-center justify-center">
        <div className="h-8 w-8 animate-spin rounded-full border-4 border-primary border-t-transparent" />
      </div>
    );
  }

  if (session.status === "unauthenticated") {
    // Redirect is in-flight via useEffect. Render null to avoid flashing the
    // protected content before the navigation resolves.
    return null;
  }

  return <>{children}</>;
}
