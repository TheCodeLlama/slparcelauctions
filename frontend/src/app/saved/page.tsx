import type { Metadata } from "next";
import { Suspense } from "react";
import { LoadingSpinner } from "@/components/ui/LoadingSpinner";
import { SavedPageContent } from "./SavedPageContent";

export const metadata: Metadata = {
  title: "Saved Parcels · SLPA",
  robots: { index: false, follow: false },
};

/**
 * {@code /saved} — the URL-synced companion to the Curator Tray drawer.
 * Server component shell; the authenticated-only state + URL-synced
 * query live in {@link SavedPageContent}. The {@link Suspense} boundary
 * is required so the Next.js 16 prerender can bail out on the
 * {@code useSearchParams} read inside the client body without failing
 * the build.
 */
export default function SavedPage() {
  return (
    <Suspense fallback={<LoadingSpinner label="Loading saved parcels..." />}>
      <SavedPageContent />
    </Suspense>
  );
}
