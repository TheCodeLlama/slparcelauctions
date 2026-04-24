import type { Metadata } from "next";
import { SavedPageContent } from "./SavedPageContent";

export const metadata: Metadata = {
  title: "Saved Parcels · SLPA",
  robots: { index: false, follow: false },
};

/**
 * {@code /saved} — the URL-synced companion to the Curator Tray drawer.
 * Server component shell; the authenticated-only state + URL-synced
 * query live in {@link SavedPageContent}.
 */
export default function SavedPage() {
  return <SavedPageContent />;
}
