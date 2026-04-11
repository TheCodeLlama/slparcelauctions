"use client";

import { useEffect, useState } from "react";
import { useTheme } from "next-themes";
import { IconButton } from "./IconButton";
import { Sun, Moon } from "./icons";

export function ThemeToggle() {
  const { resolvedTheme, setTheme } = useTheme();
  const [mounted, setMounted] = useState(false);

  useEffect(() => {
    setMounted(true);
  }, []);

  if (!mounted) return null;

  return (
    <IconButton
      aria-label="Toggle theme"
      variant="tertiary"
      onClick={() => setTheme(resolvedTheme === "dark" ? "light" : "dark")}
    >
      {resolvedTheme === "dark" ? <Sun /> : <Moon />}
    </IconButton>
  );
}
