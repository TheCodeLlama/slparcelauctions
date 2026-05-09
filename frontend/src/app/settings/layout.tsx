import type { ReactNode } from "react";
import { SettingsNav } from "@/components/settings/SettingsNav";

export default function SettingsLayout({ children }: { children: ReactNode }) {
  return (
    <div className="mx-auto max-w-4xl px-4 py-8">
      <h1 className="text-xl font-bold tracking-tight font-display font-bold mb-6">Settings</h1>
      <SettingsNav />
      {children}
    </div>
  );
}
