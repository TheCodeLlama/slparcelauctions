// frontend/src/app/dashboard/page.tsx
"use client";

import { RequireAuth } from "@/components/auth/RequireAuth";
import { useAuth } from "@/lib/auth";
import { PageHeader } from "@/components/layout/PageHeader";

export default function DashboardPage() {
  return (
    <RequireAuth>
      <DashboardContent />
    </RequireAuth>
  );
}

function DashboardContent() {
  const session = useAuth();
  // RequireAuth guarantees this component only renders when authenticated,
  // but TypeScript doesn't know that — narrow the union explicitly.
  if (session.status !== "authenticated") return null;

  return (
    <>
      <PageHeader
        title="Dashboard"
        subtitle={`Signed in as ${session.user.email}`}
      />
      <div className="mx-auto max-w-4xl px-4 py-8">
        <p className="text-body-md text-on-surface-variant">
          Your bids, listings, and sales will appear here. Real dashboard
          content lands in a future task.
        </p>
      </div>
    </>
  );
}
