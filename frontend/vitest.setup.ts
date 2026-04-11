// frontend/vitest.setup.ts
import "@testing-library/jest-dom/vitest";
import { cleanup } from "@testing-library/react";
import { afterEach, vi } from "vitest";

// jsdom does not implement ResizeObserver. Headless UI v2's Menu uses it
// internally (via @floating-ui) for panel positioning. Without this stub,
// tests that open a Dropdown crash with "ReferenceError: ResizeObserver is
// not defined". The stub does nothing — positioning isn't exercised in jsdom.
global.ResizeObserver = class ResizeObserver {
  observe() {}
  unobserve() {}
  disconnect() {}
};

// jsdom does not implement window.matchMedia. next-themes calls it in a
// useEffect to detect the system color-scheme preference. Without this stub,
// every test that renders a ThemeProvider crashes with
// "TypeError: window.matchMedia is not a function".
Object.defineProperty(window, "matchMedia", {
  writable: true,
  configurable: true,
  value: vi.fn((query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: vi.fn(),
    removeListener: vi.fn(),
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
    dispatchEvent: vi.fn(),
  })),
});

// next/font/google only resolves inside the Next build pipeline.
vi.mock("next/font/google", () => ({
  Manrope: () => ({
    className: "font-manrope",
    variable: "--font-manrope",
  }),
}));

// next/navigation hooks only work inside a real Next request context.
vi.mock("next/navigation", () => ({
  usePathname: () => "/",
  useRouter: () => ({
    push: vi.fn(),
    replace: vi.fn(),
    refresh: vi.fn(),
    back: vi.fn(),
    forward: vi.fn(),
    prefetch: vi.fn(),
  }),
  useSearchParams: () => new URLSearchParams(),
}));

// Without globals:false-friendly automatic cleanup, RTL leaves the previous
// test's DOM in place and the next test gets stale nodes.
afterEach(() => {
  cleanup();
});
