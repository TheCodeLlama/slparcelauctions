"use client";

import { useRouter } from "next/navigation";
import { Avatar, Dropdown } from "@/components/ui";
import { useLogout } from "@/lib/auth";
import type { AuthUser } from "@/lib/auth";
import { useCurrentUser } from "@/lib/user";

type UserMenuDropdownProps = {
  user: AuthUser;
};

export function UserMenuDropdown({ user }: UserMenuDropdownProps) {
  const logout = useLogout();
  const router = useRouter();
  // AuthUser is the slim JWT-decoded session shape — it omits
  // profilePicUrl. Pulling the full /me response here gives us the
  // avatar URL + updatedAt for cache-busting; the query is
  // already-cached for any other consumer on the page.
  const { data: currentUser } = useCurrentUser();

  const displayLabel = user.displayName ?? user.username;

  const trigger = (
    <button
      type="button"
      aria-label="User menu"
      className="flex items-center gap-2 rounded-full focus:outline-none focus-visible:ring-2 focus-visible:ring-brand"
    >
      <Avatar
        src={currentUser?.profilePicUrl ?? undefined}
        name={displayLabel}
        alt="User avatar"
        size="sm"
        cacheBust={currentUser?.updatedAt}
      />
      <span className="hidden md:inline text-xs text-fg">{displayLabel}</span>
    </button>
  );

  const items = [
    {
      label: "Dashboard",
      onSelect: () => router.push("/dashboard"),
    },
    {
      label: "Profile",
      onSelect: () => router.push(`/users/${user.publicId}`),
    },
    {
      label: "Settings",
      onSelect: () => router.push("/settings/profile"),
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
