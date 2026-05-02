"use client";

import { useEffect, useState } from "react";
import { useTheme } from "next-themes";
import { IconButton } from "./IconButton";
import { Sun, Moon } from "./icons";

export function ThemeToggle() {
  const { resolvedTheme, setTheme } = useTheme();
  const [mounted, setMounted] = useState(false);

  useEffect(() => {
    setMounted(true); // eslint-disable-line react-hooks/set-state-in-effect
  }, []);

  const isDark = mounted && resolvedTheme === "dark";

  return (
    <IconButton
      aria-label={mounted ? (isDark ? "Switch to light mode" : "Switch to dark mode") : "Toggle theme"}
      variant="tertiary"
      onClick={mounted ? () => setTheme(isDark ? "light" : "dark") : undefined}
      disabled={!mounted}
    >
      {mounted ? (isDark ? <Sun /> : <Moon />) : <span aria-hidden className="block h-[18px] w-[18px]" />}
    </IconButton>
  );
}
