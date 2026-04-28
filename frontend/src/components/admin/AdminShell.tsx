"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { type ReactNode } from "react";
import { useAuth } from "@/lib/auth";
import { useAdminStats } from "@/hooks/admin/useAdminStats";
import { cn } from "@/lib/cn";

type SidebarItem = {
  label: string;
  href: string;
  badge?: number;
};

export function AdminShell({ children }: { children: ReactNode }) {
  const pathname = usePathname();
  const session = useAuth();
  const user = session.status === "authenticated" ? session.user : null;
  const { data: stats } = useAdminStats();

  const items: SidebarItem[] = [
    { label: "Dashboard", href: "/admin" },
    { label: "Fraud Flags", href: "/admin/fraud-flags", badge: stats?.queues.openFraudFlags },
    { label: "Reports", href: "/admin/reports", badge: stats?.queues.openReports },
    { label: "Disputes", href: "/admin/disputes", badge: stats?.queues.activeDisputes },
    { label: "Bans", href: "/admin/bans" },
    { label: "Users", href: "/admin/users" },
    { label: "Infrastructure", href: "/admin/infrastructure" },
  ];

  return (
    <div className="grid grid-cols-[200px_1fr] min-h-[calc(100vh-4rem)]">
      <aside className="bg-surface-container border-r border-outline-variant px-4 py-5 flex flex-col gap-1">
        <div className="text-[11px] uppercase tracking-wider opacity-50 mb-3">Admin</div>
        {items.map((item) => {
          const active =
            pathname === item.href ||
            (item.href !== "/admin" && pathname?.startsWith(item.href));
          return (
            <Link
              key={item.href}
              href={item.href}
              className={cn(
                "flex items-center justify-between rounded-md px-3 py-2 text-sm",
                active
                  ? "bg-secondary-container text-on-secondary-container font-medium"
                  : "opacity-85 hover:opacity-100"
              )}
            >
              <span>{item.label}</span>
              {item.badge !== undefined && item.badge > 0 && (
                <span className="bg-error text-on-error rounded-full px-1.5 py-0.5 text-[10px] ml-1">
                  {item.badge}
                </span>
              )}
            </Link>
          );
        })}
        <div className="mt-auto px-3 py-2 text-[11px] opacity-50">
          {user?.displayName ?? user?.email}
          <br />
          <span className="text-[10px] text-primary">ADMIN</span>
        </div>
      </aside>
      <main className="px-7 py-6">{children}</main>
    </div>
  );
}
