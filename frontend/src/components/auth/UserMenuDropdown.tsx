"use client";

import { useRouter } from "next/navigation";
import { Avatar, Dropdown } from "@/components/ui";
import { useLogout } from "@/lib/auth";
import type { AuthUser } from "@/lib/auth";

type UserMenuDropdownProps = {
  user: AuthUser;
};

export function UserMenuDropdown({ user }: UserMenuDropdownProps) {
  const logout = useLogout();
  const router = useRouter();

  const displayLabel = user.displayName ?? user.email.split("@")[0];

  const trigger = (
    <button
      type="button"
      aria-label="User menu"
      className="flex items-center gap-2 rounded-full focus:outline-none focus-visible:ring-2 focus-visible:ring-primary"
    >
      <Avatar name={displayLabel} alt="User avatar" size="sm" />
      <span className="hidden md:inline text-body-sm text-on-surface">{displayLabel}</span>
    </button>
  );

  const items = [
    {
      label: "Dashboard",
      onSelect: () => router.push("/dashboard"),
    },
    {
      label: "Profile",
      onSelect: () => router.push("/profile"),
    },
    ...(user.role === "ADMIN"
      ? [{ label: "Admin", onSelect: () => router.push("/admin") }]
      : []),
    {
      label: "Sign Out",
      onSelect: () => logout.mutate(),
      danger: true,
    },
  ];

  return <Dropdown trigger={trigger} items={items} />;
}
