"use client";

// For simple menus. When we need dividers, group headers, or custom item content,
// switch to a `children`-based API with <Dropdown.Item> subcomponents.
// Headless UI primitives support both patterns.

import { Menu, MenuButton, MenuItem, MenuItems } from "@headlessui/react";
import type { ReactNode } from "react";
import { cn } from "@/lib/cn";

export type DropdownItem = {
  label: string;
  onSelect: () => void;
  icon?: ReactNode;
  disabled?: boolean;
  danger?: boolean;
};

type DropdownProps = {
  trigger: ReactNode;
  items: DropdownItem[];
  align?: "start" | "end";
  className?: string;
};

export function Dropdown({
  trigger,
  items,
  align = "end",
  className,
}: DropdownProps) {
  return (
    <Menu as="div" className={cn("relative inline-block text-left", className)}>
      <MenuButton as="div">{trigger}</MenuButton>
      <MenuItems
        anchor={align === "end" ? "bottom end" : "bottom start"}
        className="mt-2 min-w-48 bg-surface-raised rounded-lg shadow-md p-2 focus:outline-none"
      >
        {items.map((item, i) => (
          <MenuItem key={i} disabled={item.disabled}>
            {({ focus }) => (
              <button
                type="button"
                onClick={() => {
                  if (!item.disabled) item.onSelect();
                }}
                disabled={item.disabled}
                className={cn(
                  "w-full text-left px-3 py-2 rounded-sm text-sm flex items-center gap-2",
                  focus && !item.disabled && "bg-bg-muted",
                  item.danger ? "text-danger" : "text-fg",
                  item.disabled && "opacity-50 cursor-not-allowed"
                )}
              >
                {item.icon && (
                  <span className="[&_svg]:size-4 [&_svg]:stroke-[1.5]">
                    {item.icon}
                  </span>
                )}
                {item.label}
              </button>
            )}
          </MenuItem>
        ))}
      </MenuItems>
    </Menu>
  );
}
