// frontend/vitest.config.ts
import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";
import { fileURLToPath } from "url";

export default defineConfig({
  plugins: [react()],
  resolve: {
    // MUST mirror tsconfig.json `paths`. Vitest does not read tsconfig
    // automatically, so the alias has to be declared in both places.
    alias: {
      "@": fileURLToPath(new URL("./src", import.meta.url)),
    },
  },
  test: {
    environment: "jsdom",
    setupFiles: "./vitest.setup.ts",
    globals: false,
    css: true,
    exclude: ["node_modules", ".next", "dist", "e2e/**"],
    include: ["src/**/*.{test,spec}.{ts,tsx}"],
  },
});
