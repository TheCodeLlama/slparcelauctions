# Task 01-06: Frontend Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stand up the Next.js layout shell, theme system, component primitive library, `lib/` contracts, routing, and Vitest test infrastructure that every subsequent frontend task will build on. No real pages, no real auth, no API calls — just the toolbox.

**Architecture:** Tailwind 4 `@theme` directive owns design tokens (M3 vocabulary, both light defaults and dark `.dark` overrides, full M3 type scale). `next-themes` handles class-based dark mode. TanStack Query is wired but unused. A single `<Providers>` client component composes ThemeProvider → QueryClientProvider. Eight UI primitives in `components/ui/` use a `cn()` helper (clsx + tailwind-merge) for class composition. Five layout components in `components/layout/` plus a single `NavLink` consumed by both Header and MobileMenu. Eleven placeholder pages exercise routing end-to-end. Headless UI provides `Dropdown`'s and `MobileMenu`'s a11y machinery (focus trap, escape, scroll lock, keyboard nav). All components are token-driven — zero `dark:` variants and zero hex colors anywhere in `src/components` or `src/app`.

**Tech Stack:** Next.js 16.2.3, React 19.2.4, TypeScript 5, Tailwind CSS 4, next-themes, @tanstack/react-query 5, @headlessui/react 2, lucide-react, clsx, tailwind-merge, Vitest 3, @testing-library/react 16, jsdom, @vitejs/plugin-react.

**Spec:** [`docs/superpowers/specs/2026-04-10-task-01-06-frontend-foundation-design.md`](../specs/2026-04-10-task-01-06-frontend-foundation-design.md). The spec is the source of truth — if the plan and the spec ever disagree, the spec wins; surface the discrepancy.

---

## File Structure

The complete file inventory is in **spec §3.1**. Summary:

| Action | Path | Purpose |
|---|---|---|
| Move | `frontend/app/` → `frontend/src/app/` | `src/`-based layout per CONVENTIONS |
| Rewrite | `frontend/src/app/layout.tsx` | RootLayout with Manrope font + Providers + AppShell |
| Rewrite | `frontend/src/app/page.tsx` | Landing placeholder |
| Rewrite | `frontend/src/app/globals.css` | Full M3 token block + type scale + dark overrides |
| Create | `frontend/src/app/providers.tsx` | `<Providers>` composing ThemeProvider + QueryClientProvider |
| Create | `frontend/src/app/{browse,dashboard,login,register,forgot-password,about,terms,contact,partners}/page.tsx` | 9 RSC placeholder pages |
| Create | `frontend/src/app/auction/[id]/page.tsx` | Dynamic placeholder with breadcrumbs |
| Create | `frontend/src/components/ui/{Button,IconButton,Input,Card,StatusBadge,Avatar,ThemeToggle,Dropdown}.tsx` + sibling `*.test.tsx` | 8 primitives + 8 tests |
| Create | `frontend/src/components/ui/icons.ts` | lucide-react barrel |
| Create | `frontend/src/components/ui/index.ts` | Public barrel |
| Create | `frontend/src/components/layout/{AppShell,Header,MobileMenu,Footer,PageHeader,NavLink}.tsx` + sibling `*.test.tsx` | 6 layout components + 6 tests |
| Create | `frontend/src/lib/{api,auth,cn}.ts` + sibling `*.test.ts` | 3 lib modules + 3 tests |
| Create | `frontend/src/test/render.tsx` | `renderWithProviders` helper |
| Create | `frontend/vitest.config.ts` | Vitest config (jsdom, css true, alias mirror) |
| Create | `frontend/vitest.setup.ts` | RTL matchers + global mocks (next/font, next/navigation) + cleanup |
| Create | `frontend/scripts/verify-coverage.sh` | Bash script: every primitive has a sibling test |
| Modify | `frontend/package.json` | Add 6 runtime deps + 7 dev deps + test/verify scripts |
| Modify | `frontend/tsconfig.json` | Add `@/*` path alias |
| Modify | `README.md` (project root) | Frontend section sweep — mention `npm run test`, `npm run verify`, component library shape |

---

## Important Context for the Implementer

Read this section before starting any task. It captures cross-cutting patterns that repeat across many tasks; the per-task instructions assume you've already internalized them.

### 1. The `cn()` helper is mandatory in every primitive

`src/lib/cn.ts` exports `cn(...inputs)` which composes class strings via `clsx` + `tailwind-merge`. **Every primitive that accepts a consumer `className` prop merges via `cn(baseClasses, variantClasses, className)` so consumer classes always win conflicts.** Without `tailwind-merge`, passing `className="p-8"` to a component with base `p-4` produces `"p-4 p-8"` which Tailwind resolves to "whichever comes last in the generated CSS" — unreliable.

Skipping `cn()` in any primitive is a regression. The grep rules don't catch it; review does. Every primitive's "renders with correct base classes" test must include an assertion that consumer `className` wins via merge.

### 2. The test render helper is mandatory in every test

Every test file imports `renderWithProviders` (and any RTL utilities it needs) from `@/test/render`, **never directly from `@testing-library/react`**. The helper wraps the rendered tree in `ThemeProvider` and a fresh `QueryClient` per test. Tests that import `render` directly will get confusing `useTheme` errors when they hit the first hook.

The helper accepts `theme?: "light" | "dark"` (default `"light"`) and `forceTheme?: boolean` (default `false`). Pass `forceTheme: true` only when the test must lock the theme for snapshot stability — the default lets `next-themes` actually transition so integration tests like `ThemeToggle` work.

### 3. Global mocks live in `vitest.setup.ts` — don't re-mock per file

`next/font/google` and `next/navigation` are mocked globally in `vitest.setup.ts`. Tests **don't** re-mock them at the file level. Tests that need a specific `usePathname` value override per-test:

```ts
import { usePathname } from "next/navigation";
import { vi, beforeEach } from "vitest";

const mockedUsePathname = vi.mocked(usePathname);

beforeEach(() => {
  mockedUsePathname.mockReset();
  mockedUsePathname.mockReturnValue("/");
});
```

Without `mockReset` in `beforeEach`, a test that sets `/browse` leaks into the next test.

### 4. `aria-label` is required in TypeScript types for IconButton

`IconButtonProps` declares `"aria-label": string` (not optional). The compiler is the enforcement mechanism — review and lint don't catch the 20% that slip through. Same principle: `Avatar.alt` is required. Apply to any future image-wrapping primitive.

### 5. Zero `dark:` variants, zero hex colors, zero inline styles

These three rules are enforced by `npm run verify` (Task 27). The grep is the enforcement mechanism. **The `@variant dark (.dark &);` directive is deliberately omitted** from `globals.css` — without it, any `dark:` write produces no CSS, so the failure teaches the rule. Don't add the directive back.

### 6. The full mount chain

```
RootLayout (RSC)
  └─ <Providers> (client)
       └─ ThemeProvider (next-themes)
            └─ QueryClientProvider (TanStack Query)
                 └─ AppShell (RSC)
                      ├─ Header (client)
                      ├─ <main>{children}</main>
                      └─ Footer (RSC)
```

ThemeProvider is outermost so theme state never invalidates on query activity. AppShell is inside both providers so `Header.tsx`'s `useTheme()` and `useAuth()` hooks have ancestors. `children` is each page's RSC.

### 7. The `@/*` alias must live in BOTH `tsconfig.json` AND `vitest.config.ts`

Vitest does **not** read tsconfig paths. If the alias is only in tsconfig, every test that imports from `@/components/...` fails with "Cannot find module." Both files in Task 1 + Task 4.

### 8. Next.js 16 dynamic-route `params` is a `Promise`

`/auction/[id]/page.tsx` must `await params` before destructuring. Trying to destructure directly produces a runtime error.

### 9. Server vs client component split

| Component | Type | Why |
|---|---|---|
| `app/layout.tsx` | RSC | Server-side metadata, font, mounts Providers |
| `app/providers.tsx` | client | `useState` for QueryClient |
| `AppShell` | RSC | Pure structural composition |
| `Header` | client | `useState` (mobile menu, scrolled), `useTheme` indirectly, `useAuth` |
| `Footer` | RSC | Pure presentation; year via `new Date()` |
| `MobileMenu` | client | Headless UI Dialog state |
| `PageHeader` | RSC | Pure presentation; `actions` is a `ReactNode` slot |
| `NavLink` | client | `usePathname()` for active state |
| `ThemeToggle` | client | `useTheme()` |
| `Dropdown` | client | Headless UI Menu state |
| All page routes | RSC | Thin compose of `<PageHeader />` |

### 10. Headless UI is the behavior-primitives library

`Dropdown` (Task 18) uses `@headlessui/react`'s `Menu`. `MobileMenu` (Task 23) uses `Dialog`. Don't roll your own focus trap, escape, scroll lock, ARIA, or keyboard nav — Headless UI has been solving these since 2020. Future tasks reach for `Listbox`, `Combobox`, `Disclosure` from the same library.

### 11. The `useScrolled` pattern is inline, not extracted

Header uses an inline `useState` + `useEffect` to track scroll position. **Don't pre-extract** to `src/lib/hooks/useScrolled.ts`. The extraction happens when a second consumer (sticky sidebar, sticky bid panel) appears in a future task.

### 12. Every commit message uses Conventional Commits

`feat:`, `fix:`, `chore:`, `docs:`, `test:`, `refactor:`. Scope the commit by feature when possible: `feat(ui): add Button primitive`, `feat(layout): wire AppShell into RootLayout`, `chore(deps): install vitest and friends`. Per CONVENTIONS.md.

### 13. NEVER skip hooks or sign attribution

Per the user's saved memory: no `--no-verify`, no `Co-Authored-By` trailers, no AI/Anthropic mentions in commit messages. If a pre-commit hook fails, fix the underlying issue.

---

## Phase A — Infrastructure (Tasks 1–4)

### Task 1: `src/` restructure + tsconfig path alias

**Files:**
- Move: `frontend/app/` → `frontend/src/app/` (`git mv`, preserves history)
- Modify: `frontend/tsconfig.json` — add `@/*` path alias
- Verify: `frontend/next.config.ts` — Next 16 auto-detects `src/app/`, no change needed

- [ ] **Step 1: Move the `app/` directory under `src/`**

```bash
cd frontend
git mv app src/app
ls src/app
# Expected: favicon.ico  globals.css  layout.tsx  page.tsx
```

- [ ] **Step 2: Add `@/*` path alias to `tsconfig.json`**

Open `frontend/tsconfig.json`. Inside `compilerOptions`, add `baseUrl` and `paths`:

```json
{
  "compilerOptions": {
    "baseUrl": ".",
    "paths": { "@/*": ["./src/*"] }
  }
}
```

If `baseUrl` already exists from the scaffold, leave it. If `paths` already exists, merge `"@/*": ["./src/*"]` into it.

- [ ] **Step 3: Verify the build still works**

Run: `npm run build`
Expected: `Compiled successfully` — no errors. Next 16 detects `src/app/` automatically.

- [ ] **Step 4: Commit**

```bash
git add tsconfig.json src/
git status  # confirm only tsconfig + the moved files
git commit -m "chore(frontend): restructure to src/ layout and add @/* alias"
```

---

### Task 2: Install all new dependencies

**Files:**
- Modify: `frontend/package.json`
- Modify: `frontend/package-lock.json` (auto-updated by npm)

- [ ] **Step 1: Install runtime dependencies**

```bash
cd frontend
npm install next-themes @tanstack/react-query @headlessui/react lucide-react clsx tailwind-merge
```

Expected: six packages added without errors. If `lucide-react` reports a peer-dependency warning about React 19, ignore it — lucide-react supports React 19.

- [ ] **Step 2: Install dev dependencies**

```bash
npm install -D vitest @vitejs/plugin-react @vitest/ui jsdom @testing-library/react @testing-library/jest-dom @testing-library/user-event
```

Expected: seven packages added. `@testing-library/react` 16+ supports React 19.

- [ ] **Step 3: Verify the package.json reflects all 13 new entries**

```bash
grep -E '"(next-themes|@tanstack/react-query|@headlessui/react|lucide-react|clsx|tailwind-merge|vitest|@vitejs/plugin-react|@vitest/ui|jsdom|@testing-library/react|@testing-library/jest-dom|@testing-library/user-event)"' package.json | wc -l
```

Expected: `13`.

- [ ] **Step 4: Verify nothing broke**

```bash
npm run build
```

Expected: `Compiled successfully`. (We haven't wired any new code yet; this just confirms the package additions don't conflict.)

- [ ] **Step 5: Commit**

```bash
git add package.json package-lock.json
git commit -m "chore(deps): install next-themes, tanstack query, headlessui, lucide, clsx, tailwind-merge, vitest"
```

---

### Task 3: Add npm scripts (test + verify)

**Files:**
- Modify: `frontend/package.json` — `scripts` block

- [ ] **Step 1: Replace the `scripts` block**

Open `frontend/package.json`. Replace the `scripts` object with:

```json
{
  "scripts": {
    "dev": "next dev",
    "build": "next build",
    "start": "next start",
    "lint": "eslint",
    "test": "vitest run",
    "test:watch": "vitest",
    "test:ui": "vitest --ui",
    "verify:no-dark-variants": "! grep -rEn 'dark:' src/components src/app",
    "verify:no-hex-colors": "! grep -rEn 'className.*#[0-9a-fA-F]{3,8}|style=.*#[0-9a-fA-F]{3,8}' src/components src/app",
    "verify:no-inline-styles": "! grep -rn 'style={' src/components src/app",
    "verify:coverage": "bash scripts/verify-coverage.sh",
    "verify": "npm run verify:no-dark-variants && npm run verify:no-hex-colors && npm run verify:no-inline-styles && npm run verify:coverage"
  }
}
```

The `!` prefix on the verify scripts inverts grep's exit code: grep returns 0 when it finds matches (which we want to fail) and 1 when it doesn't (which we want to pass).

- [ ] **Step 2: Verify the scripts list correctly**

```bash
npm run
```

Expected output includes the 11 scripts above. The verify scripts won't pass yet — `src/components` doesn't exist and `scripts/verify-coverage.sh` doesn't exist. That's fine; we wire them later.

- [ ] **Step 3: Commit**

```bash
git add package.json
git commit -m "chore(frontend): add test and verify npm scripts"
```

---

### Task 4: Vitest infrastructure (config + setup + render helper)

**Files:**
- Create: `frontend/vitest.config.ts`
- Create: `frontend/vitest.setup.ts`
- Create: `frontend/src/test/render.tsx`
- Create: `frontend/src/test/render.smoke.test.tsx` — smoke test that the helper itself works

- [ ] **Step 1: Create `vitest.config.ts`**

```ts
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
```

- [ ] **Step 2: Create `vitest.setup.ts`**

```ts
// frontend/vitest.setup.ts
import "@testing-library/jest-dom/vitest";
import { cleanup } from "@testing-library/react";
import { afterEach, vi } from "vitest";

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
```

- [ ] **Step 3: Create the render helper**

```tsx
// frontend/src/test/render.tsx
import { render, type RenderOptions } from "@testing-library/react";
import { ThemeProvider } from "next-themes";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { ReactElement, ReactNode } from "react";

type RenderWithProvidersOptions = Omit<RenderOptions, "wrapper"> & {
  /** Initial theme. Default "light" for snapshot stability. */
  theme?: "light" | "dark";
  /**
   * If true, locks the theme via `forcedTheme` so `setTheme()` becomes
   * a no-op. Defaults to false so tests can observe theme transitions.
   */
  forceTheme?: boolean;
};

function makeWrapper(theme: "light" | "dark", force: boolean) {
  return function Wrapper({ children }: { children: ReactNode }) {
    const queryClient = new QueryClient({
      defaultOptions: {
        queries: { retry: false },
        mutations: { retry: false },
      },
    });

    return (
      <ThemeProvider
        attribute="class"
        defaultTheme={theme}
        enableSystem={false}
        forcedTheme={force ? theme : undefined}
      >
        <QueryClientProvider client={queryClient}>
          {children}
        </QueryClientProvider>
      </ThemeProvider>
    );
  };
}

export function renderWithProviders(
  ui: ReactElement,
  { theme = "light", forceTheme = false, ...options }: RenderWithProvidersOptions = {}
) {
  return render(ui, { wrapper: makeWrapper(theme, forceTheme), ...options });
}

// Re-export RTL utilities so test files only import from one place.
export { screen, within, fireEvent, waitFor } from "@testing-library/react";
export { default as userEvent } from "@testing-library/user-event";
```

- [ ] **Step 4: Write a smoke test that the helper actually works**

```tsx
// frontend/src/test/render.smoke.test.tsx
import { describe, expect, it } from "vitest";
import { renderWithProviders, screen } from "./render";

describe("renderWithProviders", () => {
  it("renders a child element inside the provider stack without crashing", () => {
    renderWithProviders(<div data-testid="probe">hello</div>);
    expect(screen.getByTestId("probe")).toHaveTextContent("hello");
  });
});
```

- [ ] **Step 5: Run the smoke test**

```bash
cd frontend
npm run test -- render.smoke
```

Expected: 1 passed. If it fails with "Cannot find module 'next-themes'" or similar, the deps from Task 2 didn't install correctly — re-run `npm install`.

- [ ] **Step 6: Commit**

```bash
git add vitest.config.ts vitest.setup.ts src/test/
git commit -m "test(frontend): add vitest config, setup, and renderWithProviders helper"
```

---

## Phase B — Theme + root layout (Tasks 5–6)

### Task 5: Full `globals.css` with M3 token block + type scale

**Files:**
- Rewrite: `frontend/src/app/globals.css`

This file is the visual foundation. Spec §4.1 has the canonical token block — copy from there. Below is the complete content for convenience.

- [ ] **Step 1: Replace `globals.css` entirely**

```css
/* frontend/src/app/globals.css */
@import "tailwindcss";

@theme {
  /* ===== Surface family (light defaults) ===== */
  --color-background: #f7f9fb;
  --color-on-background: #191c1e;
  --color-surface: #f7f9fb;
  --color-on-surface: #191c1e;
  --color-on-surface-variant: #4e4638;
  --color-surface-tint: #7b5808;
  --color-surface-bright: #f7f9fb;
  --color-surface-dim: #d8dadc;
  --color-surface-variant: #e0e3e5;
  --color-surface-container-lowest: #ffffff;
  --color-surface-container-low: #f2f4f6;
  --color-surface-container: #eceef0;
  --color-surface-container-high: #e6e8ea;
  --color-surface-container-highest: #e0e3e5;

  /* ===== Primary (warm amber) ===== */
  --color-primary: #7b5808;
  --color-on-primary: #ffffff;
  --color-primary-container: #c99e4c;
  --color-on-primary-container: #4e3600;
  --color-primary-fixed: #ffdea7;
  --color-on-primary-fixed: #271900;
  --color-primary-fixed-dim: #eec06a;
  --color-on-primary-fixed-variant: #5e4200;

  /* ===== Secondary (cool slate) ===== */
  --color-secondary: #545f73;
  --color-on-secondary: #ffffff;
  --color-secondary-container: #d5e0f8;
  --color-on-secondary-container: #586377;
  --color-secondary-fixed: #d8e3fb;
  --color-on-secondary-fixed: #111c2d;
  --color-secondary-fixed-dim: #bcc7de;
  --color-on-secondary-fixed-variant: #3c475a;

  /* ===== Tertiary (cool blue) ===== */
  --color-tertiary: #3f5f93;
  --color-on-tertiary: #ffffff;
  --color-tertiary-container: #86a5de;
  --color-on-tertiary-container: #163a6c;
  --color-tertiary-fixed: #d6e3ff;
  --color-on-tertiary-fixed: #001b3e;
  --color-tertiary-fixed-dim: #aac7ff;
  --color-on-tertiary-fixed-variant: #254779;

  /* ===== Error / "Ending Soon" status ===== */
  --color-error: #ba1a1a;
  --color-on-error: #ffffff;
  --color-error-container: #ffdad6;
  --color-on-error-container: #93000a;

  /* ===== Outline ===== */
  --color-outline: #807666;
  --color-outline-variant: #d2c5b2;

  /* ===== Inverse ===== */
  --color-inverse-surface: #2d3133;
  --color-inverse-on-surface: #eff1f3;
  --color-inverse-primary: #eec06a;

  /* ===== Typography ===== */
  --font-sans: var(--font-manrope), system-ui, sans-serif;
  --font-display: var(--font-manrope), system-ui, sans-serif;
  --font-body: var(--font-manrope), system-ui, sans-serif;

  /* ===== M3 Type Scale ===== */
  --text-display-lg: 3.5625rem;
  --text-display-lg--line-height: 4rem;
  --text-display-lg--font-weight: 400;
  --text-display-lg--letter-spacing: -0.25px;
  --text-display-md: 2.8125rem;
  --text-display-md--line-height: 3.25rem;
  --text-display-md--font-weight: 400;
  --text-display-md--letter-spacing: 0px;
  --text-display-sm: 2.25rem;
  --text-display-sm--line-height: 2.75rem;
  --text-display-sm--font-weight: 400;
  --text-display-sm--letter-spacing: 0px;

  --text-headline-lg: 2rem;
  --text-headline-lg--line-height: 2.5rem;
  --text-headline-lg--font-weight: 400;
  --text-headline-lg--letter-spacing: 0px;
  --text-headline-md: 1.75rem;
  --text-headline-md--line-height: 2.25rem;
  --text-headline-md--font-weight: 400;
  --text-headline-md--letter-spacing: 0px;
  --text-headline-sm: 1.5rem;
  --text-headline-sm--line-height: 2rem;
  --text-headline-sm--font-weight: 400;
  --text-headline-sm--letter-spacing: 0px;

  --text-title-lg: 1.375rem;
  --text-title-lg--line-height: 1.75rem;
  --text-title-lg--font-weight: 400;
  --text-title-lg--letter-spacing: 0px;
  --text-title-md: 1rem;
  --text-title-md--line-height: 1.5rem;
  --text-title-md--font-weight: 500;
  --text-title-md--letter-spacing: 0.15px;
  --text-title-sm: 0.875rem;
  --text-title-sm--line-height: 1.25rem;
  --text-title-sm--font-weight: 500;
  --text-title-sm--letter-spacing: 0.1px;

  --text-body-lg: 1rem;
  --text-body-lg--line-height: 1.5rem;
  --text-body-lg--font-weight: 400;
  --text-body-lg--letter-spacing: 0.5px;
  --text-body-md: 0.875rem;
  --text-body-md--line-height: 1.25rem;
  --text-body-md--font-weight: 400;
  --text-body-md--letter-spacing: 0.25px;
  --text-body-sm: 0.75rem;
  --text-body-sm--line-height: 1rem;
  --text-body-sm--font-weight: 400;
  --text-body-sm--letter-spacing: 0.4px;

  --text-label-lg: 0.875rem;
  --text-label-lg--line-height: 1.25rem;
  --text-label-lg--font-weight: 500;
  --text-label-lg--letter-spacing: 0.1px;
  --text-label-md: 0.75rem;
  --text-label-md--line-height: 1rem;
  --text-label-md--font-weight: 500;
  --text-label-md--letter-spacing: 0.5px;
  --text-label-sm: 0.6875rem;
  --text-label-sm--line-height: 1rem;
  --text-label-sm--font-weight: 500;
  --text-label-sm--letter-spacing: 0.5px;

  /* ===== Radius ===== */
  --radius-sm: 0.5rem;
  --radius-default: 1rem;
  --radius-lg: 1.5rem;
  --radius-full: 9999px;

  /* ===== Shadows ===== */
  --shadow-soft: 0 8px 24px rgba(25, 28, 30, 0.04);
  --shadow-elevated: 0 20px 40px rgba(25, 28, 30, 0.06);
}

.dark {
  --color-background: #121416;
  --color-on-background: #e2e2e5;
  --color-surface: #121416;
  --color-on-surface: #e2e2e5;
  --color-on-surface-variant: #d2c5b2;
  --color-surface-tint: #eec06a;
  --color-surface-bright: #37393b;
  --color-surface-dim: #121416;
  --color-surface-variant: #333537;
  --color-surface-container-lowest: #0c0e10;
  --color-surface-container-low: #1a1c1e;
  --color-surface-container: #1e2022;
  --color-surface-container-high: #282a2c;
  --color-surface-container-highest: #333537;

  --color-primary: #eec06a;
  --color-on-primary: #412d00;

  --color-secondary: #dcc39a;
  --color-on-secondary: #3d2e11;
  --color-secondary-container: #584627;
  --color-on-secondary-container: #ceb58d;
  --color-secondary-fixed: #fadfb4;
  --color-on-secondary-fixed: #261901;
  --color-secondary-fixed-dim: #dcc39a;
  --color-on-secondary-fixed-variant: #554425;

  --color-tertiary: #aac7ff;
  --color-on-tertiary: #053062;

  --color-error: #ffb4ab;
  --color-on-error: #690005;
  --color-error-container: #93000a;
  --color-on-error-container: #ffdad6;

  --color-outline: #9b8f7e;
  --color-outline-variant: #4e4638;

  --color-inverse-surface: #e2e2e5;
  --color-inverse-on-surface: #2f3133;
  --color-inverse-primary: #7b5808;
}
```

**Critical reminders:**
- Do **not** add `@variant dark (.dark &);`. The omission is intentional (see Important Context #5).
- The `.dark` block contains only tokens that differ from light. Tokens absent from `.dark` are intentionally shared.
- Every value comes from the Stitch reference HTML — don't tweak.

- [ ] **Step 2: Verify the build still works**

```bash
npm run build
```

Expected: `Compiled successfully`. The unused tokens compile to unused CSS custom properties; Tailwind only generates utility classes for tokens that are referenced. Bundle cost: zero.

- [ ] **Step 3: Commit**

```bash
git add src/app/globals.css
git commit -m "feat(theme): add full M3 token block, type scale, and dark mode overrides"
```

---

### Task 6: Manrope font + `<Providers>` + `RootLayout`

**Files:**
- Create: `frontend/src/app/providers.tsx`
- Rewrite: `frontend/src/app/layout.tsx`
- Rewrite: `frontend/src/app/page.tsx` — minimal placeholder so the build is green

**Note:** AppShell is wired in Task 25 (after the layout components exist). For this task, RootLayout renders `<Providers>{children}</Providers>` directly, no shell.

- [ ] **Step 1: Create `providers.tsx`**

```tsx
// frontend/src/app/providers.tsx
"use client";

import { ThemeProvider } from "next-themes";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { useState, type ReactNode } from "react";

export function Providers({ children }: { children: ReactNode }) {
  const [queryClient] = useState(
    () =>
      new QueryClient({
        defaultOptions: {
          queries: {
            staleTime: 60_000,
            refetchOnWindowFocus: false,
            retry: 1,
          },
        },
      })
  );

  return (
    <ThemeProvider attribute="class" defaultTheme="dark" enableSystem>
      <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
    </ThemeProvider>
  );
}
```

- [ ] **Step 2: Rewrite `layout.tsx`**

```tsx
// frontend/src/app/layout.tsx
import type { Metadata } from "next";
import { Manrope } from "next/font/google";
import "./globals.css";
import { Providers } from "./providers";

const manrope = Manrope({
  subsets: ["latin"],
  display: "swap",
  variable: "--font-manrope",
  weight: ["300", "400", "500", "600", "700", "800"],
});

export const metadata: Metadata = {
  title: {
    default: "SLPA — Second Life Parcel Auctions",
    template: "%s · SLPA",
  },
  description: "Player-to-player land auctions for Second Life.",
};

export default function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <html lang="en" className={manrope.variable} suppressHydrationWarning>
      <body className="min-h-screen font-sans bg-surface text-on-surface antialiased">
        <Providers>{children}</Providers>
      </body>
    </html>
  );
}
```

- [ ] **Step 3: Rewrite `page.tsx` as a minimal placeholder**

```tsx
// frontend/src/app/page.tsx
export default function HomePage() {
  return (
    <div className="mx-auto max-w-7xl px-6 py-16">
      <h1 className="text-display-md text-on-surface">SLPA</h1>
      <p className="mt-4 text-body-lg text-on-surface-variant">
        Player-to-player land auctions for Second Life — coming soon.
      </p>
    </div>
  );
}
```

This will be replaced with `<PageHeader />` in Task 26 once PageHeader exists.

- [ ] **Step 4: Run the dev server and visually verify**

```bash
npm run dev
```

Expected: server starts on `http://localhost:3000`. Visit it manually:
- Page renders the "SLPA" heading and tagline
- Background is the warm ivory color (`#f7f9fb`) by default — wait, `defaultTheme="dark"` so it should be the dark `#121416`
- No console errors
- No hydration warnings

If you see a hydration warning about className mismatch on `<html>`, verify `suppressHydrationWarning` is on the `<html>` tag.

Stop the dev server with Ctrl+C.

- [ ] **Step 5: Run the build to confirm production parses**

```bash
npm run build
```

Expected: `Compiled successfully`.

- [ ] **Step 6: Commit**

```bash
git add src/app/providers.tsx src/app/layout.tsx src/app/page.tsx
git commit -m "feat(layout): wire Manrope font, Providers, and RootLayout"
```

---

## Phase C — `lib/` utilities (Tasks 7–9)

### Task 7: `lib/cn.ts` + tests

**Files:**
- Create: `frontend/src/lib/cn.ts`
- Create: `frontend/src/lib/cn.test.ts`

- [ ] **Step 1: Write the failing test**

```ts
// frontend/src/lib/cn.test.ts
import { describe, it, expect } from "vitest";
import { cn } from "./cn";

describe("cn", () => {
  it("dedupes conflicting Tailwind utilities so consumer wins", () => {
    expect(cn("p-4", "p-8")).toBe("p-8");
  });

  it("merges truthy conditionals and filters falsy/nullish values", () => {
    const hasError = true;
    const isDisabled = false;
    expect(
      cn("p-4", hasError && "ring-error", isDisabled && "opacity-50", null, undefined, "text-on-surface")
    ).toBe("p-4 ring-error text-on-surface");
  });
});
```

- [ ] **Step 2: Run the test, expect FAIL**

```bash
npm run test -- cn
```

Expected: FAIL with `Cannot find module './cn'`.

- [ ] **Step 3: Write the implementation**

```ts
// frontend/src/lib/cn.ts
import { type ClassValue, clsx } from "clsx";
import { twMerge } from "tailwind-merge";

/**
 * Compose Tailwind class strings. Filters falsy values via clsx, then
 * dedupes conflicting Tailwind utilities via tailwind-merge so that
 * consumer-passed `className` always wins over component base classes.
 */
export function cn(...inputs: ClassValue[]): string {
  return twMerge(clsx(inputs));
}
```

- [ ] **Step 4: Run the test, expect PASS**

```bash
npm run test -- cn
```

Expected: 2 passed.

- [ ] **Step 5: Commit**

```bash
git add src/lib/cn.ts src/lib/cn.test.ts
git commit -m "feat(lib): add cn class composition helper with tailwind-merge"
```

---

### Task 8: `lib/api.ts` + tests

**Files:**
- Create: `frontend/src/lib/api.ts`
- Create: `frontend/src/lib/api.test.ts`

- [ ] **Step 1: Write the failing test**

```ts
// frontend/src/lib/api.test.ts
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { api, ApiError, isApiError } from "./api";

describe("api", () => {
  beforeEach(() => {
    vi.stubGlobal("fetch", vi.fn());
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("returns parsed JSON on a 2xx response", async () => {
    vi.mocked(fetch).mockResolvedValueOnce(
      new Response(JSON.stringify({ id: 1, name: "alice" }), {
        status: 200,
        headers: { "Content-Type": "application/json" },
      })
    );

    const result = await api.get<{ id: number; name: string }>("/api/users/1");
    expect(result).toEqual({ id: 1, name: "alice" });
  });

  it("throws ApiError with the parsed ProblemDetail on 4xx", async () => {
    const problem = {
      status: 400,
      title: "Validation Failed",
      detail: "email must be valid",
      errors: { email: "must be a well-formed email address" },
    };
    vi.mocked(fetch).mockResolvedValueOnce(
      new Response(JSON.stringify(problem), {
        status: 400,
        headers: { "Content-Type": "application/json" },
      })
    );

    await expect(api.post("/api/users", { email: "bad" })).rejects.toMatchObject({
      status: 400,
      problem: {
        status: 400,
        errors: { email: "must be a well-formed email address" },
      },
    });
  });

  it("synthesizes a ProblemDetail when the error body is non-JSON", async () => {
    vi.mocked(fetch).mockResolvedValueOnce(
      new Response("<html>502 Bad Gateway</html>", {
        status: 502,
        statusText: "Bad Gateway",
      })
    );

    let caught: unknown;
    try {
      await api.get("/api/health");
    } catch (e) {
      caught = e;
    }
    expect(isApiError(caught)).toBe(true);
    expect((caught as ApiError).status).toBe(502);
    expect((caught as ApiError).problem.title).toBe("Bad Gateway");
  });

  it("returns undefined on a 204 No Content response", async () => {
    vi.mocked(fetch).mockResolvedValueOnce(new Response(null, { status: 204 }));
    const result = await api.delete<void>("/api/users/1");
    expect(result).toBeUndefined();
  });

  it("URLSearchParams-encodes the params field, stripping undefined values", async () => {
    vi.mocked(fetch).mockResolvedValueOnce(
      new Response(JSON.stringify([]), {
        status: 200,
        headers: { "Content-Type": "application/json" },
      })
    );

    await api.get("/api/auctions", {
      params: { status: "active", page: 2, ended: undefined, includeDrafts: false },
    });

    expect(fetch).toHaveBeenCalledWith(
      expect.stringContaining("/api/auctions?status=active&page=2&includeDrafts=false"),
      expect.any(Object)
    );
    // Confirm the undefined value is NOT in the URL
    const calledUrl = vi.mocked(fetch).mock.calls[0][0] as string;
    expect(calledUrl).not.toContain("ended=");
  });
});
```

- [ ] **Step 2: Run the test, expect FAIL**

```bash
npm run test -- api
```

Expected: FAIL with `Cannot find module './api'`.

- [ ] **Step 3: Write the implementation**

```ts
// frontend/src/lib/api.ts

/**
 * RFC 7807 Problem Details. Matches the shape that
 * backend/src/main/java/.../common/GlobalExceptionHandler.java emits.
 */
export type ProblemDetail = {
  type?: string;
  title?: string;
  status: number;
  detail?: string;
  instance?: string;
  errors?: Record<string, string>;
  [key: string]: unknown;
};

/**
 * Thrown by every non-2xx response. Carries the parsed ProblemDetail
 * rather than the raw Response so the body is consumed exactly once.
 */
export class ApiError extends Error {
  readonly status: number;
  readonly problem: ProblemDetail;

  constructor(problem: ProblemDetail) {
    super(problem.detail ?? problem.title ?? `HTTP ${problem.status}`);
    this.name = "ApiError";
    this.status = problem.status;
    this.problem = problem;
  }
}

/**
 * Type guard. Prefer this over `instanceof ApiError` across module boundaries —
 * bundler edge cases in RSC + client splits can produce duplicate class identities.
 */
export function isApiError(e: unknown): e is ApiError {
  return e instanceof ApiError;
}

const BASE_URL = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";

type RequestOptions = Omit<RequestInit, "body"> & {
  body?: unknown;
  params?: Record<string, string | number | boolean | undefined>;
};

function buildPath(path: string, params?: RequestOptions["params"]): string {
  if (!params) return path;
  const sp = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined) continue;
    sp.append(key, String(value));
  }
  const qs = sp.toString();
  return qs ? `${path}?${qs}` : path;
}

async function request<T>(
  path: string,
  { body, headers, params, ...rest }: RequestOptions = {}
): Promise<T> {
  const response = await fetch(`${BASE_URL}${buildPath(path, params)}`, {
    credentials: "include",
    ...rest,
    headers: {
      Accept: "application/json",
      ...(body !== undefined ? { "Content-Type": "application/json" } : {}),
      ...headers,
    },
    body: body !== undefined ? JSON.stringify(body) : undefined,
  });

  if (!response.ok) {
    let problem: ProblemDetail;
    try {
      problem = (await response.json()) as ProblemDetail;
    } catch {
      problem = { status: response.status, title: response.statusText };
    }
    throw new ApiError(problem);
  }

  if (response.status === 204) return undefined as T;
  return (await response.json()) as T;
}

export const api = {
  get: <T>(path: string, options?: RequestOptions) =>
    request<T>(path, { ...options, method: "GET" }),
  post: <T>(path: string, body?: unknown, options?: RequestOptions) =>
    request<T>(path, { ...options, method: "POST", body }),
  put: <T>(path: string, body?: unknown, options?: RequestOptions) =>
    request<T>(path, { ...options, method: "PUT", body }),
  delete: <T>(path: string, options?: RequestOptions) =>
    request<T>(path, { ...options, method: "DELETE" }),
};
```

- [ ] **Step 4: Run the test, expect PASS**

```bash
npm run test -- api
```

Expected: 5 passed.

- [ ] **Step 5: Commit**

```bash
git add src/lib/api.ts src/lib/api.test.ts
git commit -m "feat(lib): add typed fetch wrapper with ProblemDetail normalization"
```

---

### Task 9: `lib/auth.ts` stub + tests

**Files:**
- Create: `frontend/src/lib/auth.ts`
- Create: `frontend/src/lib/auth.test.ts`

- [ ] **Step 1: Write the failing test**

```ts
// frontend/src/lib/auth.test.ts
import { describe, it, expect } from "vitest";
import { useAuth } from "./auth";

describe("useAuth (stub)", () => {
  it("returns an unauthenticated session", () => {
    expect(useAuth()).toEqual({ status: "unauthenticated", user: null });
  });
});
```

- [ ] **Step 2: Run the test, expect FAIL**

```bash
npm run test -- auth
```

Expected: FAIL with `Cannot find module './auth'`.

- [ ] **Step 3: Write the implementation**

```ts
// frontend/src/lib/auth.ts

/**
 * Stub auth hook. Returns an unauthenticated session.
 *
 * Replaced in Task 01-08 with a real JWT-backed implementation that
 * reads from localStorage and exposes login/logout mutations. Callers
 * MUST treat the return shape as the contract — do not add fields
 * here without updating the replacement in 01-08.
 */

export type AuthUser = {
  id: number;
  email: string;
  displayName: string;
  slAvatarUuid: string | null;
  verified: boolean;
};

export type AuthSession =
  | { status: "loading"; user: null }
  | { status: "authenticated"; user: AuthUser }
  | { status: "unauthenticated"; user: null };

export function useAuth(): AuthSession {
  return { status: "unauthenticated", user: null };
}
```

- [ ] **Step 4: Run the test, expect PASS**

```bash
npm run test -- auth
```

Expected: 1 passed.

- [ ] **Step 5: Commit**

```bash
git add src/lib/auth.ts src/lib/auth.test.ts
git commit -m "feat(lib): add useAuth stub with three-state AuthSession contract"
```

---

## Phase D — `components/ui/` primitives (Tasks 10–19)

### Task 10: `icons.ts` lucide-react barrel

**Files:**
- Create: `frontend/src/components/ui/icons.ts`

No tests for this file — it's a pure re-export. The icons are exercised by primitive tests downstream.

- [ ] **Step 1: Create the barrel**

```ts
// frontend/src/components/ui/icons.ts
export {
  Sun,
  Moon,
  Bell,
  Search,
  Menu as MenuIcon,
  X,
  Check,
  ChevronDown,
  ChevronRight,
  ChevronLeft,
  ChevronUp,
  Eye,
  EyeOff,
  User,
  LogOut,
  Settings,
  Loader2,
} from "lucide-react";
```

`Menu` is renamed to `MenuIcon` to avoid colliding with the future `Menu` primitive (Headless UI export).

- [ ] **Step 2: Verify the file compiles by running the existing test suite**

```bash
npm run test
```

Expected: All previously-passing tests still pass (8 cases from Tasks 7–9 plus the smoke test from Task 4).

- [ ] **Step 3: Commit**

```bash
git add src/components/ui/icons.ts
git commit -m "feat(ui): add lucide-react icon barrel"
```

---

### Task 11: `Button` primitive + tests

**Files:**
- Create: `frontend/src/components/ui/Button.tsx`
- Create: `frontend/src/components/ui/Button.test.tsx`

- [ ] **Step 1: Write the failing test**

```tsx
// frontend/src/components/ui/Button.test.tsx
import { describe, it, expect, vi } from "vitest";
import { renderWithProviders, screen, userEvent } from "@/test/render";
import { Button } from "./Button";

describe("Button", () => {
  it("renders with the primary variant gradient and merges consumer className via cn", () => {
    renderWithProviders(<Button className="w-32">Bid</Button>);
    const button = screen.getByRole("button", { name: "Bid" });
    // Variant base classes
    expect(button.className).toContain("bg-gradient-to-br");
    expect(button.className).toContain("from-primary");
    expect(button.className).toContain("to-primary-container");
    expect(button.className).toContain("text-on-primary");
    // Consumer className wins
    expect(button.className).toContain("w-32");
  });

  it("renders the secondary ghost variant", () => {
    renderWithProviders(<Button variant="secondary">Cancel</Button>);
    const button = screen.getByRole("button", { name: "Cancel" });
    expect(button.className).toContain("bg-surface-container-lowest");
    expect(button.className).toContain("text-on-surface");
    expect(button.className).toContain("shadow-soft");
  });

  it("renders the tertiary text-only variant", () => {
    renderWithProviders(<Button variant="tertiary">Forgot password?</Button>);
    const button = screen.getByRole("button", { name: "Forgot password?" });
    expect(button.className).toContain("text-primary");
    expect(button.className).toContain("hover:underline");
    expect(button.className).not.toContain("bg-gradient-to-br");
  });

  it("disables the button and renders a spinner when loading", () => {
    renderWithProviders(<Button loading leftIcon={<span data-testid="left-icon" />}>Save</Button>);
    const button = screen.getByRole("button", { name: /Save/ });
    expect(button).toBeDisabled();
    // Spinner replaces leftIcon when loading
    expect(screen.queryByTestId("left-icon")).toBeNull();
    expect(button.querySelector(".animate-spin")).not.toBeNull();
  });

  it("renders leftIcon and rightIcon in the correct slots", () => {
    renderWithProviders(
      <Button
        leftIcon={<span data-testid="left">L</span>}
        rightIcon={<span data-testid="right">R</span>}
      >
        With Icons
      </Button>
    );
    expect(screen.getByTestId("left")).toBeInTheDocument();
    expect(screen.getByTestId("right")).toBeInTheDocument();
  });

  it("fires onClick when not loading or disabled", async () => {
    const onClick = vi.fn();
    renderWithProviders(<Button onClick={onClick}>Click me</Button>);
    await userEvent.click(screen.getByRole("button", { name: "Click me" }));
    expect(onClick).toHaveBeenCalledTimes(1);
  });
});
```

- [ ] **Step 2: Run the test, expect FAIL**

```bash
npm run test -- Button
```

Expected: FAIL — `Cannot find module './Button'`.

- [ ] **Step 3: Write the implementation**

```tsx
// frontend/src/components/ui/Button.tsx
import { forwardRef, type ButtonHTMLAttributes, type ReactNode } from "react";
import { Loader2 } from "./icons";
import { cn } from "@/lib/cn";

type ButtonVariant = "primary" | "secondary" | "tertiary";
type ButtonSize = "sm" | "md" | "lg";

type ButtonProps = {
  variant?: ButtonVariant;
  size?: ButtonSize;
  loading?: boolean;
  leftIcon?: ReactNode;
  rightIcon?: ReactNode;
  fullWidth?: boolean;
  children: ReactNode;
} & Omit<ButtonHTMLAttributes<HTMLButtonElement>, "children">;

const variantClasses: Record<ButtonVariant, string> = {
  primary:
    "bg-gradient-to-br from-primary to-primary-container text-on-primary shadow-soft hover:shadow-elevated",
  secondary:
    "bg-surface-container-lowest text-on-surface shadow-soft hover:shadow-elevated",
  tertiary: "text-primary hover:underline underline-offset-4",
};

const sizeClasses: Record<ButtonSize, string> = {
  sm: "h-9 px-4 text-label-md",
  md: "h-11 px-5 text-label-lg",
  lg: "h-12 px-6 text-title-md",
};

const baseClasses =
  "inline-flex items-center justify-center gap-2 rounded-default font-medium transition-all disabled:opacity-50 disabled:pointer-events-none";

export const Button = forwardRef<HTMLButtonElement, ButtonProps>(function Button(
  {
    variant = "primary",
    size = "md",
    loading = false,
    leftIcon,
    rightIcon,
    fullWidth = false,
    disabled,
    className,
    children,
    type = "button",
    ...rest
  },
  ref
) {
  const isDisabled = disabled || loading;
  const renderedLeft = loading ? (
    <Loader2 className="size-4 animate-spin" />
  ) : (
    leftIcon
  );

  return (
    <button
      ref={ref}
      type={type}
      disabled={isDisabled}
      className={cn(
        baseClasses,
        variantClasses[variant],
        sizeClasses[size],
        fullWidth && "w-full",
        className
      )}
      {...rest}
    >
      {renderedLeft}
      <span>{children}</span>
      {rightIcon}
    </button>
  );
});
```

- [ ] **Step 4: Run the test, expect PASS**

```bash
npm run test -- Button
```

Expected: 6 passed.

- [ ] **Step 5: Commit**

```bash
git add src/components/ui/Button.tsx src/components/ui/Button.test.tsx
git commit -m "feat(ui): add Button primitive with primary/secondary/tertiary variants"
```

---

### Task 12: `IconButton` primitive + tests

**Files:**
- Create: `frontend/src/components/ui/IconButton.tsx`
- Create: `frontend/src/components/ui/IconButton.test.tsx`

- [ ] **Step 1: Write the failing test**

```tsx
// frontend/src/components/ui/IconButton.test.tsx
import { describe, it, expect, vi } from "vitest";
import { renderWithProviders, screen, userEvent } from "@/test/render";
import { IconButton } from "./IconButton";
import { Bell } from "./icons";

describe("IconButton", () => {
  it("requires an aria-label and renders it on the button", () => {
    renderWithProviders(
      <IconButton aria-label="Notifications">
        <Bell />
      </IconButton>
    );
    expect(screen.getByRole("button", { name: "Notifications" })).toBeInTheDocument();
  });

  it("fires onClick", async () => {
    const onClick = vi.fn();
    renderWithProviders(
      <IconButton aria-label="Toggle theme" onClick={onClick}>
        <Bell />
      </IconButton>
    );
    await userEvent.click(screen.getByRole("button", { name: "Toggle theme" }));
    expect(onClick).toHaveBeenCalledTimes(1);
  });

  it("renders the secondary variant by default and merges consumer className", () => {
    renderWithProviders(
      <IconButton aria-label="Search" className="md:hidden">
        <Bell />
      </IconButton>
    );
    const button = screen.getByRole("button", { name: "Search" });
    expect(button.className).toContain("rounded-full");
    expect(button.className).toContain("md:hidden");
  });

  it("bakes the 1.5 stroke width into the SVG via the [&_svg]:stroke-[1.5] selector class", () => {
    renderWithProviders(
      <IconButton aria-label="Bell">
        <Bell />
      </IconButton>
    );
    const button = screen.getByRole("button", { name: "Bell" });
    expect(button.className).toContain("[&_svg]:stroke-[1.5]");
  });
});
```

- [ ] **Step 2: Run the test, expect FAIL**

```bash
npm run test -- IconButton
```

Expected: FAIL — `Cannot find module './IconButton'`.

- [ ] **Step 3: Write the implementation**

```tsx
// frontend/src/components/ui/IconButton.tsx
import { forwardRef, type ButtonHTMLAttributes, type ReactNode } from "react";
import { cn } from "@/lib/cn";

type IconButtonVariant = "primary" | "secondary" | "tertiary";
type IconButtonSize = "sm" | "md" | "lg";

type IconButtonProps = {
  variant?: IconButtonVariant;
  size?: IconButtonSize;
  "aria-label": string;
  children: ReactNode;
} & Omit<ButtonHTMLAttributes<HTMLButtonElement>, "children" | "aria-label">;

const variantClasses: Record<IconButtonVariant, string> = {
  primary:
    "bg-gradient-to-br from-primary to-primary-container text-on-primary shadow-soft hover:shadow-elevated",
  secondary:
    "bg-surface-container-lowest text-on-surface shadow-soft hover:shadow-elevated",
  tertiary: "text-on-surface-variant hover:bg-surface-container-low",
};

const sizeClasses: Record<IconButtonSize, string> = {
  sm: "h-9 w-9",
  md: "h-11 w-11",
  lg: "h-12 w-12",
};

const baseClasses =
  "inline-flex items-center justify-center rounded-full transition-all disabled:opacity-50 disabled:pointer-events-none [&_svg]:size-5 [&_svg]:stroke-[1.5]";

export const IconButton = forwardRef<HTMLButtonElement, IconButtonProps>(
  function IconButton(
    {
      variant = "secondary",
      size = "md",
      className,
      children,
      type = "button",
      ...rest
    },
    ref
  ) {
    return (
      <button
        ref={ref}
        type={type}
        className={cn(
          baseClasses,
          variantClasses[variant],
          sizeClasses[size],
          className
        )}
        {...rest}
      >
        {children}
      </button>
    );
  }
);
```

- [ ] **Step 4: Run the test, expect PASS**

```bash
npm run test -- IconButton
```

Expected: 4 passed.

- [ ] **Step 5: Commit**

```bash
git add src/components/ui/IconButton.tsx src/components/ui/IconButton.test.tsx
git commit -m "feat(ui): add IconButton primitive with required aria-label"
```

---

### Task 13: `Input` primitive + tests

**Files:**
- Create: `frontend/src/components/ui/Input.tsx`
- Create: `frontend/src/components/ui/Input.test.tsx`

- [ ] **Step 1: Write the failing test**

```tsx
// frontend/src/components/ui/Input.test.tsx
import { useState } from "react";
import { describe, it, expect } from "vitest";
import { renderWithProviders, screen, userEvent } from "@/test/render";
import { Input } from "./Input";

describe("Input", () => {
  it("associates label with input via htmlFor/id and merges consumer className", () => {
    renderWithProviders(<Input label="Email" className="max-w-md" />);
    const input = screen.getByLabelText("Email");
    expect(input).toBeInTheDocument();
    expect(input.tagName).toBe("INPUT");
    expect(input.className).toContain("max-w-md");
    expect(input.className).toContain("bg-surface-container-low");
  });

  it("shows error text and applies the error ring when error prop is set", () => {
    renderWithProviders(<Input label="Email" error="must be a valid email" helperText="we never share this" />);
    expect(screen.getByText("must be a valid email")).toBeInTheDocument();
    // helperText is replaced by error
    expect(screen.queryByText("we never share this")).toBeNull();
    const input = screen.getByLabelText("Email");
    expect(input.className).toContain("ring-error");
  });

  it("renders leftIcon with appropriate padding offset", () => {
    renderWithProviders(
      <Input label="Search" leftIcon={<span data-testid="left">🔍</span>} />
    );
    expect(screen.getByTestId("left")).toBeInTheDocument();
    const input = screen.getByLabelText("Search");
    expect(input.className).toContain("pl-10");
  });

  it("works as a controlled input", async () => {
    function Wrapper() {
      const [value, setValue] = useState("hello");
      return <Input label="Name" value={value} onChange={(e) => setValue(e.target.value)} />;
    }
    renderWithProviders(<Wrapper />);
    const input = screen.getByLabelText("Name") as HTMLInputElement;
    expect(input.value).toBe("hello");
    await userEvent.clear(input);
    await userEvent.type(input, "world");
    expect(input.value).toBe("world");
  });

  it("works as an uncontrolled input with defaultValue", () => {
    renderWithProviders(<Input label="Name" defaultValue="default" />);
    const input = screen.getByLabelText("Name") as HTMLInputElement;
    expect(input.value).toBe("default");
  });
});
```

- [ ] **Step 2: Run the test, expect FAIL**

```bash
npm run test -- Input
```

Expected: FAIL — `Cannot find module './Input'`.

- [ ] **Step 3: Write the implementation**

```tsx
// frontend/src/components/ui/Input.tsx
import {
  forwardRef,
  useId,
  type InputHTMLAttributes,
  type ReactNode,
} from "react";
import { cn } from "@/lib/cn";

type InputProps = {
  label?: string;
  helperText?: string;
  error?: string;
  leftIcon?: ReactNode;
  rightIcon?: ReactNode;
  fullWidth?: boolean;
} & InputHTMLAttributes<HTMLInputElement>;

const baseClasses =
  "h-12 w-full rounded-default bg-surface-container-low text-on-surface placeholder:text-on-surface-variant px-4 ring-1 ring-transparent transition-all focus:bg-surface-container-lowest focus:outline-none focus:ring-primary";

export const Input = forwardRef<HTMLInputElement, InputProps>(function Input(
  {
    label,
    helperText,
    error,
    leftIcon,
    rightIcon,
    fullWidth = true,
    className,
    id,
    ...rest
  },
  ref
) {
  const generatedId = useId();
  const inputId = id ?? generatedId;
  const showError = Boolean(error);

  return (
    <div className={cn("flex flex-col gap-1", fullWidth && "w-full")}>
      {label && (
        <label
          htmlFor={inputId}
          className="text-label-md text-on-surface-variant"
        >
          {label}
        </label>
      )}
      <div className="relative">
        {leftIcon && (
          <span className="absolute left-3 top-1/2 -translate-y-1/2 text-on-surface-variant [&_svg]:size-5 [&_svg]:stroke-[1.5]">
            {leftIcon}
          </span>
        )}
        <input
          ref={ref}
          id={inputId}
          className={cn(
            baseClasses,
            leftIcon && "pl-10",
            rightIcon && "pr-10",
            showError && "ring-error focus:ring-error",
            className
          )}
          aria-invalid={showError || undefined}
          {...rest}
        />
        {rightIcon && (
          <span className="absolute right-3 top-1/2 -translate-y-1/2 text-on-surface-variant [&_svg]:size-5 [&_svg]:stroke-[1.5]">
            {rightIcon}
          </span>
        )}
      </div>
      {showError ? (
        <span className="text-body-sm text-error">{error}</span>
      ) : helperText ? (
        <span className="text-body-sm text-on-surface-variant">{helperText}</span>
      ) : null}
    </div>
  );
});
```

- [ ] **Step 4: Run the test, expect PASS**

```bash
npm run test -- Input
```

Expected: 5 passed.

- [ ] **Step 5: Commit**

```bash
git add src/components/ui/Input.tsx src/components/ui/Input.test.tsx
git commit -m "feat(ui): add Input primitive with label/error/icon support"
```

---

### Task 14: `Card` compound primitive + tests

**Files:**
- Create: `frontend/src/components/ui/Card.tsx`
- Create: `frontend/src/components/ui/Card.test.tsx`

- [ ] **Step 1: Write the failing test**

```tsx
// frontend/src/components/ui/Card.test.tsx
import { describe, it, expect } from "vitest";
import { renderWithProviders, screen } from "@/test/render";
import { Card } from "./Card";

describe("Card", () => {
  it("renders children and merges consumer className", () => {
    renderWithProviders(
      <Card className="max-w-md" data-testid="card">
        <p>hello</p>
      </Card>
    );
    const card = screen.getByTestId("card");
    expect(card.className).toContain("bg-surface-container-lowest");
    expect(card.className).toContain("rounded-default");
    expect(card.className).toContain("shadow-soft");
    expect(card.className).toContain("max-w-md");
    expect(screen.getByText("hello")).toBeInTheDocument();
  });

  it("renders Card.Header, Card.Body, Card.Footer in compound usage", () => {
    renderWithProviders(
      <Card>
        <Card.Header data-testid="header">Title</Card.Header>
        <Card.Body data-testid="body">Body content</Card.Body>
        <Card.Footer data-testid="footer">Actions</Card.Footer>
      </Card>
    );
    expect(screen.getByTestId("header")).toHaveTextContent("Title");
    expect(screen.getByTestId("body")).toHaveTextContent("Body content");
    expect(screen.getByTestId("footer")).toHaveTextContent("Actions");
  });

  it("does not render any border classes (no-line rule)", () => {
    renderWithProviders(<Card data-testid="card">x</Card>);
    const card = screen.getByTestId("card");
    expect(card.className).not.toMatch(/\bborder\b/);
  });
});
```

- [ ] **Step 2: Run the test, expect FAIL**

```bash
npm run test -- Card
```

Expected: FAIL — `Cannot find module './Card'`.

- [ ] **Step 3: Write the implementation**

```tsx
// frontend/src/components/ui/Card.tsx
import type { HTMLAttributes, ReactNode } from "react";
import { cn } from "@/lib/cn";

type CardProps = HTMLAttributes<HTMLDivElement>;

function CardRoot({ className, children, ...rest }: CardProps) {
  return (
    <div
      className={cn(
        "bg-surface-container-lowest rounded-default shadow-soft overflow-hidden",
        className
      )}
      {...rest}
    >
      {children}
    </div>
  );
}

function CardHeader({ className, children, ...rest }: CardProps) {
  return (
    <div className={cn("p-6 pb-4", className)} {...rest}>
      {children}
    </div>
  );
}

function CardBody({ className, children, ...rest }: CardProps) {
  return (
    <div className={cn("px-6 py-4", className)} {...rest}>
      {children}
    </div>
  );
}

function CardFooter({ className, children, ...rest }: CardProps) {
  return (
    <div className={cn("p-6 pt-4", className)} {...rest}>
      {children}
    </div>
  );
}

type CardComponent = typeof CardRoot & {
  Header: typeof CardHeader;
  Body: typeof CardBody;
  Footer: typeof CardFooter;
};

export const Card = CardRoot as CardComponent;
Card.Header = CardHeader;
Card.Body = CardBody;
Card.Footer = CardFooter;

export { CardHeader, CardBody, CardFooter };
```

- [ ] **Step 4: Run the test, expect PASS**

```bash
npm run test -- Card
```

Expected: 3 passed.

- [ ] **Step 5: Commit**

```bash
git add src/components/ui/Card.tsx src/components/ui/Card.test.tsx
git commit -m "feat(ui): add Card compound primitive (Card.Header/Body/Footer)"
```

---

### Task 15: `StatusBadge` primitive + tests

**Files:**
- Create: `frontend/src/components/ui/StatusBadge.tsx`
- Create: `frontend/src/components/ui/StatusBadge.test.tsx`

- [ ] **Step 1: Write the failing test**

```tsx
// frontend/src/components/ui/StatusBadge.test.tsx
import { describe, it, expect } from "vitest";
import { renderWithProviders, screen } from "@/test/render";
import { StatusBadge } from "./StatusBadge";

describe("StatusBadge", () => {
  it("renders the active status with tertiary container tone and default label", () => {
    renderWithProviders(<StatusBadge status="active" data-testid="badge" />);
    const badge = screen.getByText("Active");
    expect(badge.className).toContain("bg-tertiary-container");
    expect(badge.className).toContain("text-on-tertiary-container");
  });

  it("renders the ending-soon status with error container tone", () => {
    renderWithProviders(<StatusBadge status="ending-soon" />);
    const badge = screen.getByText("Ending Soon");
    expect(badge.className).toContain("bg-error-container");
  });

  it("renders the warning tone in generic mode and merges consumer className", () => {
    renderWithProviders(<StatusBadge tone="warning" className="ml-auto">Custom</StatusBadge>);
    const badge = screen.getByText("Custom");
    expect(badge.className).toContain("bg-secondary-container");
    expect(badge.className).toContain("ml-auto");
  });

  it("returns null when no status, tone, or children are provided", () => {
    const { container } = renderWithProviders(<StatusBadge />);
    expect(container.firstChild).toBeNull();
  });

  it("uses children to override the default status label", () => {
    renderWithProviders(<StatusBadge status="active">12 bids</StatusBadge>);
    const badge = screen.getByText("12 bids");
    expect(badge.className).toContain("bg-tertiary-container");
  });
});
```

- [ ] **Step 2: Run the test, expect FAIL**

```bash
npm run test -- StatusBadge
```

Expected: FAIL — `Cannot find module './StatusBadge'`.

- [ ] **Step 3: Write the implementation**

```tsx
// frontend/src/components/ui/StatusBadge.tsx
import type { HTMLAttributes, ReactNode } from "react";
import { cn } from "@/lib/cn";

type AuctionStatus = "active" | "ending-soon" | "ended" | "cancelled";
type Tone = "default" | "success" | "warning" | "danger";

type StatusBadgeProps = {
  status?: AuctionStatus;
  tone?: Tone;
  children?: ReactNode;
  className?: string;
} & Omit<HTMLAttributes<HTMLSpanElement>, "children" | "className">;

const statusConfig: Record<
  AuctionStatus,
  { classes: string; label: string }
> = {
  active: {
    classes: "bg-tertiary-container text-on-tertiary-container",
    label: "Active",
  },
  "ending-soon": {
    classes: "bg-error-container text-on-error-container",
    label: "Ending Soon",
  },
  ended: {
    classes: "bg-surface-container-high text-on-surface-variant",
    label: "Ended",
  },
  cancelled: {
    classes:
      "bg-surface-container-high text-on-surface-variant line-through",
    label: "Cancelled",
  },
};

const toneClasses: Record<Tone, string> = {
  default: "bg-surface-container-high text-on-surface-variant",
  success: "bg-tertiary-container text-on-tertiary-container",
  warning: "bg-secondary-container text-on-secondary-container",
  danger: "bg-error-container text-on-error-container",
};

const baseClasses =
  "rounded-full px-3 py-1 text-label-md font-medium inline-flex items-center gap-1.5";

export function StatusBadge({
  status,
  tone,
  children,
  className,
  ...rest
}: StatusBadgeProps) {
  if (!status && !tone && !children) return null;

  const palette = status
    ? statusConfig[status].classes
    : tone
      ? toneClasses[tone]
      : toneClasses.default;

  const content = children ?? (status ? statusConfig[status].label : null);

  return (
    <span className={cn(baseClasses, palette, className)} {...rest}>
      {content}
    </span>
  );
}
```

- [ ] **Step 4: Run the test, expect PASS**

```bash
npm run test -- StatusBadge
```

Expected: 5 passed.

- [ ] **Step 5: Commit**

```bash
git add src/components/ui/StatusBadge.tsx src/components/ui/StatusBadge.test.tsx
git commit -m "feat(ui): add StatusBadge primitive with status and tone modes"
```

---

### Task 16: `Avatar` primitive + tests

**Files:**
- Create: `frontend/src/components/ui/Avatar.tsx`
- Create: `frontend/src/components/ui/Avatar.test.tsx`

- [ ] **Step 1: Write the failing test**

```tsx
// frontend/src/components/ui/Avatar.test.tsx
import { describe, it, expect } from "vitest";
import { renderWithProviders, screen } from "@/test/render";
import { Avatar } from "./Avatar";

describe("Avatar", () => {
  it("renders next/image with width/height when src is provided and merges consumer className", () => {
    renderWithProviders(
      <Avatar src="/avatar.png" alt="Heath" name="Heath Barcus" size="md" className="ring-2" />
    );
    const img = screen.getByAltText("Heath") as HTMLImageElement;
    expect(img.tagName).toBe("IMG");
    expect(img.getAttribute("width")).toBe("40");
    expect(img.getAttribute("height")).toBe("40");
    expect(img.className).toContain("rounded-full");
    expect(img.className).toContain("ring-2");
  });

  it("renders initials fallback when src is missing", () => {
    renderWithProviders(<Avatar alt="Heath" name="Heath Barcus" />);
    expect(screen.getByText("HB")).toBeInTheDocument();
  });

  it("renders ? when neither src nor name is provided", () => {
    renderWithProviders(<Avatar alt="Unknown" />);
    expect(screen.getByText("?")).toBeInTheDocument();
  });

  it("applies the correct dimensions for each size", () => {
    const { rerender } = renderWithProviders(<Avatar alt="x" name="X X" size="xs" />);
    expect(screen.getByText("XX").parentElement?.className).toContain("size-6");
    rerender(<Avatar alt="x" name="X X" size="xl" />);
    expect(screen.getByText("XX").parentElement?.className).toContain("size-20");
  });
});
```

- [ ] **Step 2: Run the test, expect FAIL**

```bash
npm run test -- Avatar
```

Expected: FAIL — `Cannot find module './Avatar'`.

- [ ] **Step 3: Write the implementation**

```tsx
// frontend/src/components/ui/Avatar.tsx
import Image from "next/image";
import { cn } from "@/lib/cn";

type AvatarSize = "xs" | "sm" | "md" | "lg" | "xl";

type AvatarProps = {
  src?: string;
  alt: string;
  name?: string;
  size?: AvatarSize;
  className?: string;
};

const sizeMap: Record<AvatarSize, { px: number; class: string }> = {
  xs: { px: 24, class: "size-6 text-label-sm" },
  sm: { px: 32, class: "size-8 text-label-md" },
  md: { px: 40, class: "size-10 text-label-lg" },
  lg: { px: 56, class: "size-14 text-title-md" },
  xl: { px: 80, class: "size-20 text-title-lg" },
};

function initialsFromName(name?: string): string {
  if (!name) return "?";
  const parts = name.trim().split(/\s+/).filter(Boolean);
  if (parts.length === 0) return "?";
  if (parts.length === 1) return parts[0][0].toUpperCase();
  return (parts[0][0] + parts[1][0]).toUpperCase();
}

export function Avatar({
  src,
  alt,
  name,
  size = "md",
  className,
}: AvatarProps) {
  const { px, class: sizeClass } = sizeMap[size];

  if (src) {
    return (
      <Image
        src={src}
        alt={alt}
        width={px}
        height={px}
        className={cn("rounded-full object-cover", className)}
      />
    );
  }

  return (
    <div
      role="img"
      aria-label={alt}
      className={cn(
        "rounded-full bg-tertiary-container text-on-tertiary-container font-semibold inline-flex items-center justify-center",
        sizeClass,
        className
      )}
    >
      {initialsFromName(name)}
    </div>
  );
}
```

- [ ] **Step 4: Run the test, expect PASS**

```bash
npm run test -- Avatar
```

Expected: 4 passed.

- [ ] **Step 5: Commit**

```bash
git add src/components/ui/Avatar.tsx src/components/ui/Avatar.test.tsx
git commit -m "feat(ui): add Avatar primitive with image and initials fallback"
```

---

### Task 17: `ThemeToggle` primitive + tests

**Files:**
- Create: `frontend/src/components/ui/ThemeToggle.tsx`
- Create: `frontend/src/components/ui/ThemeToggle.test.tsx`

- [ ] **Step 1: Write the failing test**

```tsx
// frontend/src/components/ui/ThemeToggle.test.tsx
import { describe, it, expect } from "vitest";
import { act } from "react";
import { renderWithProviders, screen, userEvent, waitFor } from "@/test/render";
import { ThemeToggle } from "./ThemeToggle";

describe("ThemeToggle", () => {
  it("renders the sun icon when theme is dark", async () => {
    renderWithProviders(<ThemeToggle />, { theme: "dark" });
    // Wait for the mounted gate to flip
    await waitFor(() => {
      expect(screen.getByRole("button", { name: "Toggle theme" })).toBeInTheDocument();
    });
    const button = screen.getByRole("button", { name: "Toggle theme" });
    const svg = button.querySelector("svg");
    expect(svg?.getAttribute("class") ?? "").toContain("lucide-sun");
  });

  it("renders the moon icon when theme is light", async () => {
    renderWithProviders(<ThemeToggle />, { theme: "light" });
    await waitFor(() => {
      expect(screen.getByRole("button", { name: "Toggle theme" })).toBeInTheDocument();
    });
    const button = screen.getByRole("button", { name: "Toggle theme" });
    const svg = button.querySelector("svg");
    expect(svg?.getAttribute("class") ?? "").toContain("lucide-moon");
  });

  it("flips the documentElement class on click (integration test, not forced theme)", async () => {
    renderWithProviders(<ThemeToggle />, { theme: "dark" });
    await waitFor(() => {
      expect(screen.getByRole("button", { name: "Toggle theme" })).toBeInTheDocument();
    });
    expect(document.documentElement.classList.contains("dark")).toBe(true);
    await act(async () => {
      await userEvent.click(screen.getByRole("button", { name: "Toggle theme" }));
    });
    await waitFor(() => {
      expect(document.documentElement.classList.contains("light")).toBe(true);
    });
  });
});
```

- [ ] **Step 2: Run the test, expect FAIL**

```bash
npm run test -- ThemeToggle
```

Expected: FAIL — `Cannot find module './ThemeToggle'`.

- [ ] **Step 3: Write the implementation**

```tsx
// frontend/src/components/ui/ThemeToggle.tsx
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
```

- [ ] **Step 4: Run the test, expect PASS**

```bash
npm run test -- ThemeToggle
```

Expected: 3 passed. If the integration test fails because `documentElement.classList` doesn't update, verify that `renderWithProviders` is **not** passing `forceTheme: true` (the default is `false`, which is what we want).

- [ ] **Step 5: Commit**

```bash
git add src/components/ui/ThemeToggle.tsx src/components/ui/ThemeToggle.test.tsx
git commit -m "feat(ui): add ThemeToggle primitive with mounted-gate hydration safety"
```

---

### Task 18: `Dropdown` primitive (Headless UI) + tests

**Files:**
- Create: `frontend/src/components/ui/Dropdown.tsx`
- Create: `frontend/src/components/ui/Dropdown.test.tsx`

- [ ] **Step 1: Write the failing test**

```tsx
// frontend/src/components/ui/Dropdown.test.tsx
import { describe, it, expect, vi } from "vitest";
import { renderWithProviders, screen, userEvent } from "@/test/render";
import { Dropdown } from "./Dropdown";

describe("Dropdown", () => {
  it("opens the menu when the trigger is clicked", async () => {
    const onProfile = vi.fn();
    renderWithProviders(
      <Dropdown
        trigger={<button>Open</button>}
        items={[{ label: "Profile", onSelect: onProfile }]}
      />
    );
    expect(screen.queryByText("Profile")).toBeNull();
    await userEvent.click(screen.getByRole("button", { name: "Open" }));
    expect(screen.getByText("Profile")).toBeInTheDocument();
  });

  it("fires onSelect and closes the menu when an item is clicked", async () => {
    const onProfile = vi.fn();
    renderWithProviders(
      <Dropdown
        trigger={<button>Open</button>}
        items={[{ label: "Profile", onSelect: onProfile }]}
      />
    );
    await userEvent.click(screen.getByRole("button", { name: "Open" }));
    await userEvent.click(screen.getByText("Profile"));
    expect(onProfile).toHaveBeenCalledTimes(1);
  });

  it("does not fire onSelect for disabled items", async () => {
    const onSelect = vi.fn();
    renderWithProviders(
      <Dropdown
        trigger={<button>Open</button>}
        items={[{ label: "Disabled", onSelect, disabled: true }]}
      />
    );
    await userEvent.click(screen.getByRole("button", { name: "Open" }));
    await userEvent.click(screen.getByText("Disabled"));
    expect(onSelect).not.toHaveBeenCalled();
  });

  it("renders danger items with text-error", async () => {
    renderWithProviders(
      <Dropdown
        trigger={<button>Open</button>}
        items={[{ label: "Sign out", onSelect: () => {}, danger: true }]}
      />
    );
    await userEvent.click(screen.getByRole("button", { name: "Open" }));
    const item = screen.getByText("Sign out");
    expect(item.className).toContain("text-error");
  });

  it("closes the menu when escape is pressed", async () => {
    renderWithProviders(
      <Dropdown
        trigger={<button>Open</button>}
        items={[{ label: "Profile", onSelect: () => {} }]}
      />
    );
    await userEvent.click(screen.getByRole("button", { name: "Open" }));
    expect(screen.getByText("Profile")).toBeInTheDocument();
    await userEvent.keyboard("{Escape}");
    expect(screen.queryByText("Profile")).toBeNull();
  });
});
```

- [ ] **Step 2: Run the test, expect FAIL**

```bash
npm run test -- Dropdown
```

Expected: FAIL — `Cannot find module './Dropdown'`.

- [ ] **Step 3: Write the implementation**

```tsx
// frontend/src/components/ui/Dropdown.tsx
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
        className="mt-2 min-w-48 bg-surface-container-lowest rounded-default shadow-elevated p-2 focus:outline-none"
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
                  "w-full text-left px-3 py-2 rounded-sm text-body-md flex items-center gap-2",
                  focus && !item.disabled && "bg-surface-container",
                  item.danger ? "text-error" : "text-on-surface",
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
```

- [ ] **Step 4: Run the test, expect PASS**

```bash
npm run test -- Dropdown
```

Expected: 5 passed.

- [ ] **Step 5: Commit**

```bash
git add src/components/ui/Dropdown.tsx src/components/ui/Dropdown.test.tsx
git commit -m "feat(ui): add Dropdown primitive built on Headless UI Menu"
```

---

### Task 19: `components/ui/index.ts` public barrel

**Files:**
- Create: `frontend/src/components/ui/index.ts`

No tests — pure re-export.

- [ ] **Step 1: Create the public barrel**

```ts
// frontend/src/components/ui/index.ts
export { Button } from "./Button";
export { IconButton } from "./IconButton";
export { Input } from "./Input";
export { Card, CardHeader, CardBody, CardFooter } from "./Card";
export { StatusBadge } from "./StatusBadge";
export { Avatar } from "./Avatar";
export { ThemeToggle } from "./ThemeToggle";
export { Dropdown, type DropdownItem } from "./Dropdown";
export * from "./icons";
```

- [ ] **Step 2: Verify the full test suite still passes**

```bash
npm run test
```

Expected: All tests pass — at this point you should have ~36 cases passing (smoke + lib×3 + ui×8 = 1 + 8 + 35 = 44; the exact count depends on intermediate counts, but every test from Tasks 4–18 must be green).

- [ ] **Step 3: Commit**

```bash
git add src/components/ui/index.ts
git commit -m "feat(ui): add public barrel for the components/ui library"
```

---

## Phase E — `components/layout/` shell (Tasks 20–25)

### Task 20: `NavLink` + tests

**Files:**
- Create: `frontend/src/components/layout/NavLink.tsx`
- Create: `frontend/src/components/layout/NavLink.test.tsx`

- [ ] **Step 1: Write the failing test**

```tsx
// frontend/src/components/layout/NavLink.test.tsx
import { describe, it, expect, vi, beforeEach } from "vitest";
import { usePathname } from "next/navigation";
import { renderWithProviders, screen } from "@/test/render";
import { NavLink } from "./NavLink";

const mockedUsePathname = vi.mocked(usePathname);

describe("NavLink", () => {
  beforeEach(() => {
    mockedUsePathname.mockReset();
    mockedUsePathname.mockReturnValue("/");
  });

  it("renders a link with the correct href", () => {
    renderWithProviders(
      <NavLink variant="header" href="/browse">Browse</NavLink>
    );
    const link = screen.getByRole("link", { name: "Browse" });
    expect(link.getAttribute("href")).toBe("/browse");
  });

  it("marks the link active and sets aria-current when pathname matches", () => {
    mockedUsePathname.mockReturnValue("/browse");
    renderWithProviders(
      <NavLink variant="header" href="/browse">Browse</NavLink>
    );
    const link = screen.getByRole("link", { name: "Browse" });
    expect(link.className).toContain("text-primary");
    expect(link.getAttribute("aria-current")).toBe("page");
  });

  it("renders inactive when pathname does not match", () => {
    mockedUsePathname.mockReturnValue("/dashboard");
    renderWithProviders(
      <NavLink variant="header" href="/browse">Browse</NavLink>
    );
    const link = screen.getByRole("link", { name: "Browse" });
    expect(link.className).toContain("text-on-surface-variant");
    expect(link.getAttribute("aria-current")).toBeNull();
  });

  it("fires the onClick handler when provided (mobile variant uses this to close)", async () => {
    const onClick = vi.fn();
    const user = (await import("@testing-library/user-event")).default.setup();
    renderWithProviders(
      <NavLink variant="mobile" href="/browse" onClick={onClick}>Browse</NavLink>
    );
    await user.click(screen.getByRole("link", { name: "Browse" }));
    expect(onClick).toHaveBeenCalledTimes(1);
  });
});
```

- [ ] **Step 2: Run the test, expect FAIL**

```bash
npm run test -- NavLink
```

Expected: FAIL — `Cannot find module './NavLink'`.

- [ ] **Step 3: Write the implementation**

```tsx
// frontend/src/components/layout/NavLink.tsx
"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import type { ReactNode } from "react";
import { cn } from "@/lib/cn";

type NavLinkProps = {
  href: string;
  variant: "header" | "mobile";
  onClick?: () => void;
  children: ReactNode;
};

export function NavLink({ href, variant, onClick, children }: NavLinkProps) {
  const pathname = usePathname();
  const isActive =
    pathname === href || (href !== "/" && pathname.startsWith(href));

  const base =
    variant === "header"
      ? "text-body-md transition-colors"
      : "text-headline-sm transition-colors";

  const state = isActive
    ? "text-primary"
    : "text-on-surface-variant hover:text-on-surface";

  return (
    <Link
      href={href}
      onClick={onClick}
      aria-current={isActive ? "page" : undefined}
      className={cn(base, state)}
    >
      {children}
    </Link>
  );
}
```

- [ ] **Step 4: Run the test, expect PASS**

```bash
npm run test -- NavLink
```

Expected: 4 passed.

- [ ] **Step 5: Commit**

```bash
git add src/components/layout/NavLink.tsx src/components/layout/NavLink.test.tsx
git commit -m "feat(layout): add NavLink with usePathname active-route detection"
```

---

### Task 21: `PageHeader` + tests

**Files:**
- Create: `frontend/src/components/layout/PageHeader.tsx`
- Create: `frontend/src/components/layout/PageHeader.test.tsx`

- [ ] **Step 1: Write the failing test**

```tsx
// frontend/src/components/layout/PageHeader.test.tsx
import { describe, it, expect } from "vitest";
import { renderWithProviders, screen } from "@/test/render";
import { PageHeader } from "./PageHeader";

describe("PageHeader", () => {
  it("renders the title as an h1", () => {
    renderWithProviders(<PageHeader title="Browse Auctions" />);
    expect(screen.getByRole("heading", { level: 1, name: "Browse Auctions" })).toBeInTheDocument();
  });

  it("renders the subtitle when provided", () => {
    renderWithProviders(<PageHeader title="Title" subtitle="A descriptive subtitle." />);
    expect(screen.getByText("A descriptive subtitle.")).toBeInTheDocument();
  });

  it("does not render the subtitle when absent", () => {
    renderWithProviders(<PageHeader title="Title" />);
    expect(screen.queryByText(/A descriptive subtitle/)).toBeNull();
  });

  it("renders the actions slot when provided", () => {
    renderWithProviders(
      <PageHeader title="Title" actions={<button>Create</button>} />
    );
    expect(screen.getByRole("button", { name: "Create" })).toBeInTheDocument();
  });

  it("renders breadcrumbs with separators and marks the last one aria-current", () => {
    renderWithProviders(
      <PageHeader
        title="Auction #42"
        breadcrumbs={[
          { label: "Browse", href: "/browse" },
          { label: "Auction #42" },
        ]}
      />
    );
    const browseLink = screen.getByRole("link", { name: "Browse" });
    expect(browseLink.getAttribute("href")).toBe("/browse");
    const current = screen.getByText("Auction #42", { selector: "span" });
    expect(current.getAttribute("aria-current")).toBe("page");
  });
});
```

- [ ] **Step 2: Run the test, expect FAIL**

```bash
npm run test -- PageHeader
```

Expected: FAIL — `Cannot find module './PageHeader'`.

- [ ] **Step 3: Write the implementation**

```tsx
// frontend/src/components/layout/PageHeader.tsx
import Link from "next/link";
import { ChevronRight } from "@/components/ui/icons";

type Breadcrumb = { label: string; href?: string };

type PageHeaderProps = {
  title: string;
  subtitle?: string;
  actions?: React.ReactNode;
  breadcrumbs?: Breadcrumb[];
};

export function PageHeader({
  title,
  subtitle,
  actions,
  breadcrumbs,
}: PageHeaderProps) {
  return (
    <div className="mx-auto max-w-7xl px-6 pt-12 pb-8">
      {breadcrumbs && breadcrumbs.length > 0 && (
        <nav aria-label="Breadcrumb" className="mb-4">
          <ol className="flex items-center gap-2 text-body-sm text-on-surface-variant">
            {breadcrumbs.map((crumb, i) => (
              <li key={i} className="flex items-center gap-2">
                {i > 0 && <ChevronRight className="size-4" />}
                {crumb.href ? (
                  <Link
                    href={crumb.href}
                    className="hover:text-on-surface transition-colors"
                  >
                    {crumb.label}
                  </Link>
                ) : (
                  <span aria-current="page">{crumb.label}</span>
                )}
              </li>
            ))}
          </ol>
        </nav>
      )}

      <div className="flex flex-col gap-4 md:flex-row md:items-end md:justify-between">
        <div>
          <h1 className="text-display-md text-on-surface">{title}</h1>
          {subtitle && (
            <p className="mt-2 text-body-lg text-on-surface-variant">
              {subtitle}
            </p>
          )}
        </div>
        {actions && <div className="flex items-center gap-3">{actions}</div>}
      </div>
    </div>
  );
}
```

- [ ] **Step 4: Run the test, expect PASS**

```bash
npm run test -- PageHeader
```

Expected: 5 passed.

- [ ] **Step 5: Commit**

```bash
git add src/components/layout/PageHeader.tsx src/components/layout/PageHeader.test.tsx
git commit -m "feat(layout): add PageHeader with title/subtitle/actions/breadcrumbs"
```

---

### Task 22: `Footer` + tests (with `vi.useFakeTimers`)

**Files:**
- Create: `frontend/src/components/layout/Footer.tsx`
- Create: `frontend/src/components/layout/Footer.test.tsx`

- [ ] **Step 1: Write the failing test**

```tsx
// frontend/src/components/layout/Footer.test.tsx
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { renderWithProviders, screen } from "@/test/render";
import { Footer } from "./Footer";

describe("Footer", () => {
  beforeEach(() => {
    // Pin time per testing principle #5 — copyright year must be deterministic.
    vi.useFakeTimers({ now: new Date("2026-06-15T12:00:00Z") });
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it("renders all four legal/site-map links with correct hrefs", () => {
    renderWithProviders(<Footer />);
    expect(screen.getByRole("link", { name: "About" }).getAttribute("href")).toBe("/about");
    expect(screen.getByRole("link", { name: "Terms" }).getAttribute("href")).toBe("/terms");
    expect(screen.getByRole("link", { name: "Contact" }).getAttribute("href")).toBe("/contact");
    expect(screen.getByRole("link", { name: "Partners" }).getAttribute("href")).toBe("/partners");
  });

  it("renders the current year in the copyright text", () => {
    renderWithProviders(<Footer />);
    expect(screen.getByText(/© 2026 SLPA/)).toBeInTheDocument();
  });
});
```

- [ ] **Step 2: Run the test, expect FAIL**

```bash
npm run test -- Footer
```

Expected: FAIL — `Cannot find module './Footer'`.

- [ ] **Step 3: Write the implementation**

```tsx
// frontend/src/components/layout/Footer.tsx
import Link from "next/link";

export function Footer() {
  return (
    <footer className="bg-surface-container-low">
      <div className="mx-auto max-w-7xl px-6 py-12">
        <div className="flex flex-col gap-8 md:flex-row md:items-center md:justify-between">
          <div className="flex flex-wrap gap-x-6 gap-y-2">
            <FooterLink href="/about">About</FooterLink>
            <FooterLink href="/terms">Terms</FooterLink>
            <FooterLink href="/contact">Contact</FooterLink>
            <FooterLink href="/partners">Partners</FooterLink>
          </div>
          <p className="text-body-sm text-on-surface-variant">
            © {new Date().getFullYear()} SLPA. Not affiliated with Linden Lab.
          </p>
        </div>
      </div>
    </footer>
  );
}

function FooterLink({
  href,
  children,
}: {
  href: string;
  children: React.ReactNode;
}) {
  return (
    <Link
      href={href}
      className="text-body-sm text-on-surface-variant hover:text-on-surface transition-colors"
    >
      {children}
    </Link>
  );
}
```

- [ ] **Step 4: Run the test, expect PASS**

```bash
npm run test -- Footer
```

Expected: 2 passed.

- [ ] **Step 5: Commit**

```bash
git add src/components/layout/Footer.tsx src/components/layout/Footer.test.tsx
git commit -m "feat(layout): add Footer with surface-shift background and pinned year test"
```

---

### Task 23: `MobileMenu` + tests

**Files:**
- Create: `frontend/src/components/layout/MobileMenu.tsx`
- Create: `frontend/src/components/layout/MobileMenu.test.tsx`

- [ ] **Step 1: Write the failing test**

```tsx
// frontend/src/components/layout/MobileMenu.test.tsx
import { describe, it, expect, vi } from "vitest";
import { renderWithProviders, screen, userEvent } from "@/test/render";
import { MobileMenu } from "./MobileMenu";

describe("MobileMenu", () => {
  it("renders the drawer when open is true", () => {
    renderWithProviders(<MobileMenu open onClose={() => {}} />);
    expect(screen.getByRole("link", { name: "Browse" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Dashboard" })).toBeInTheDocument();
  });

  it("does not render content when open is false", () => {
    renderWithProviders(<MobileMenu open={false} onClose={() => {}} />);
    expect(screen.queryByRole("link", { name: "Browse" })).toBeNull();
  });

  it("calls onClose when the close button is clicked", async () => {
    const onClose = vi.fn();
    renderWithProviders(<MobileMenu open onClose={onClose} />);
    await userEvent.click(screen.getByRole("button", { name: "Close menu" }));
    expect(onClose).toHaveBeenCalled();
  });

  it("calls onClose when escape is pressed (Headless UI Dialog handles this)", async () => {
    const onClose = vi.fn();
    renderWithProviders(<MobileMenu open onClose={onClose} />);
    await userEvent.keyboard("{Escape}");
    expect(onClose).toHaveBeenCalled();
  });
});
```

- [ ] **Step 2: Run the test, expect FAIL**

```bash
npm run test -- MobileMenu
```

Expected: FAIL — `Cannot find module './MobileMenu'`.

- [ ] **Step 3: Write the implementation**

```tsx
// frontend/src/components/layout/MobileMenu.tsx
"use client";

import { Dialog, DialogPanel } from "@headlessui/react";
import Link from "next/link";
import { X } from "@/components/ui/icons";
import { Button, IconButton } from "@/components/ui";
import { NavLink } from "./NavLink";

type MobileMenuProps = {
  open: boolean;
  onClose: () => void;
};

export function MobileMenu({ open, onClose }: MobileMenuProps) {
  return (
    <Dialog open={open} onClose={onClose} className="md:hidden relative z-50">
      <div
        className="fixed inset-0 bg-inverse-surface/40 backdrop-blur-sm"
        aria-hidden="true"
      />
      <div className="fixed inset-y-0 right-0 flex w-full max-w-sm">
        <DialogPanel className="flex w-full flex-col bg-surface-container-low p-6">
          <div className="flex justify-end">
            <IconButton
              aria-label="Close menu"
              variant="tertiary"
              onClick={onClose}
            >
              <X />
            </IconButton>
          </div>

          <nav className="mt-8 flex flex-col gap-6">
            <NavLink variant="mobile" href="/browse" onClick={onClose}>
              Browse
            </NavLink>
            <NavLink variant="mobile" href="/dashboard" onClick={onClose}>
              Dashboard
            </NavLink>
            <NavLink variant="mobile" href="/auction/new" onClick={onClose}>
              Create Listing
            </NavLink>
          </nav>

          <div className="mt-auto flex flex-col gap-3">
            <Link href="/login">
              <Button variant="tertiary" fullWidth>
                Sign in
              </Button>
            </Link>
            <Link href="/register">
              <Button variant="primary" fullWidth>
                Register
              </Button>
            </Link>
          </div>
        </DialogPanel>
      </div>
    </Dialog>
  );
}
```

- [ ] **Step 4: Run the test, expect PASS**

```bash
npm run test -- MobileMenu
```

Expected: 4 passed.

- [ ] **Step 5: Commit**

```bash
git add src/components/layout/MobileMenu.tsx src/components/layout/MobileMenu.test.tsx
git commit -m "feat(layout): add MobileMenu drawer built on Headless UI Dialog"
```

---

### Task 24: `Header` + tests

**Files:**
- Create: `frontend/src/components/layout/Header.tsx`
- Create: `frontend/src/components/layout/Header.test.tsx`

- [ ] **Step 1: Write the failing test**

```tsx
// frontend/src/components/layout/Header.test.tsx
import { describe, it, expect, vi, beforeEach } from "vitest";
import { renderWithProviders, screen, userEvent } from "@/test/render";
import { Header } from "./Header";

// Mock useAuth so we can flip auth state per test
vi.mock("@/lib/auth", () => ({
  useAuth: vi.fn(() => ({ status: "unauthenticated", user: null })),
}));

import { useAuth } from "@/lib/auth";
const mockedUseAuth = vi.mocked(useAuth);

describe("Header", () => {
  beforeEach(() => {
    mockedUseAuth.mockReset();
    mockedUseAuth.mockReturnValue({ status: "unauthenticated", user: null });
  });

  it("renders the SLPA wordmark linking to /", () => {
    renderWithProviders(<Header />);
    const logo = screen.getByRole("link", { name: "SLPA" });
    expect(logo.getAttribute("href")).toBe("/");
  });

  it("renders desktop nav links to Browse, Dashboard, Create Listing", () => {
    renderWithProviders(<Header />);
    expect(screen.getByRole("link", { name: "Browse" }).getAttribute("href")).toBe("/browse");
    expect(screen.getByRole("link", { name: "Dashboard" }).getAttribute("href")).toBe("/dashboard");
    expect(screen.getByRole("link", { name: "Create Listing" }).getAttribute("href")).toBe("/auction/new");
  });

  it("renders Sign in and Register buttons when unauthenticated", () => {
    renderWithProviders(<Header />);
    expect(screen.getByRole("link", { name: /Sign in/ })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /Register/ })).toBeInTheDocument();
  });

  it("renders the avatar dropdown when authenticated", () => {
    mockedUseAuth.mockReturnValue({
      status: "authenticated",
      user: {
        id: 1,
        email: "heath@example.com",
        displayName: "Heath Barcus",
        slAvatarUuid: null,
        verified: true,
      },
    });
    renderWithProviders(<Header />);
    expect(screen.getByRole("img", { name: "Account menu" })).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: /Sign in/ })).toBeNull();
  });

  it("opens the mobile menu when the hamburger is clicked", async () => {
    renderWithProviders(<Header />);
    expect(screen.queryByRole("button", { name: "Close menu" })).toBeNull();
    await userEvent.click(screen.getByRole("button", { name: "Open menu" }));
    expect(screen.getByRole("button", { name: "Close menu" })).toBeInTheDocument();
  });
});
```

- [ ] **Step 2: Run the test, expect FAIL**

```bash
npm run test -- Header
```

Expected: FAIL — `Cannot find module './Header'`.

- [ ] **Step 3: Write the implementation**

```tsx
// frontend/src/components/layout/Header.tsx
"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { Bell, MenuIcon } from "@/components/ui/icons";
import {
  Avatar,
  Button,
  Dropdown,
  IconButton,
  ThemeToggle,
} from "@/components/ui";
import { useAuth } from "@/lib/auth";
import { cn } from "@/lib/cn";
import { MobileMenu } from "./MobileMenu";
import { NavLink } from "./NavLink";

export function Header() {
  const [mobileMenuOpen, setMobileMenuOpen] = useState(false);
  const [scrolled, setScrolled] = useState(false);
  const { status, user } = useAuth();

  useEffect(() => {
    const onScroll = () => setScrolled(window.scrollY > 8);
    onScroll();
    window.addEventListener("scroll", onScroll, { passive: true });
    return () => window.removeEventListener("scroll", onScroll);
  }, []);

  return (
    <>
      <header
        className={cn(
          "sticky top-0 z-50 bg-surface/80 backdrop-blur-md transition-shadow",
          scrolled && "shadow-soft"
        )}
      >
        <div className="mx-auto flex h-16 max-w-7xl items-center justify-between px-6">
          <Link
            href="/"
            className="font-display text-xl font-black uppercase tracking-wider text-primary"
          >
            SLPA
          </Link>

          <nav className="hidden md:flex items-center gap-8">
            <NavLink variant="header" href="/browse">
              Browse
            </NavLink>
            <NavLink variant="header" href="/dashboard">
              Dashboard
            </NavLink>
            <NavLink variant="header" href="/auction/new">
              Create Listing
            </NavLink>
          </nav>

          <div className="flex items-center gap-2">
            <ThemeToggle />
            <IconButton aria-label="Notifications" variant="tertiary">
              <Bell />
            </IconButton>

            {status === "loading" ? null : status === "authenticated" ? (
              <Dropdown
                trigger={
                  <Avatar
                    name={user.displayName}
                    alt="Account menu"
                    size="sm"
                  />
                }
                items={[
                  { label: "Profile", onSelect: () => {} },
                  { label: "Settings", onSelect: () => {} },
                  { label: "Sign out", onSelect: () => {}, danger: true },
                ]}
              />
            ) : (
              <div className="hidden md:flex items-center gap-2">
                <Link href="/login">
                  <Button variant="tertiary" size="sm">
                    Sign in
                  </Button>
                </Link>
                <Link href="/register">
                  <Button variant="primary" size="sm">
                    Register
                  </Button>
                </Link>
              </div>
            )}

            <IconButton
              aria-label="Open menu"
              variant="tertiary"
              className="md:hidden"
              onClick={() => setMobileMenuOpen(true)}
            >
              <MenuIcon />
            </IconButton>
          </div>
        </div>
      </header>

      <MobileMenu
        open={mobileMenuOpen}
        onClose={() => setMobileMenuOpen(false)}
      />
    </>
  );
}
```

- [ ] **Step 4: Run the test, expect PASS**

```bash
npm run test -- Header
```

Expected: 5 passed.

- [ ] **Step 5: Commit**

```bash
git add src/components/layout/Header.tsx src/components/layout/Header.test.tsx
git commit -m "feat(layout): add Header with glassmorphism, scroll-aware shadow, and auth-aware right cluster"
```

---

### Task 25: `AppShell` + wire into `RootLayout`

**Files:**
- Create: `frontend/src/components/layout/AppShell.tsx`
- Create: `frontend/src/components/layout/AppShell.test.tsx`
- Modify: `frontend/src/app/layout.tsx` — wrap children in `<AppShell>`

- [ ] **Step 1: Write the failing test**

```tsx
// frontend/src/components/layout/AppShell.test.tsx
import { describe, it, expect, vi } from "vitest";
import { renderWithProviders, screen } from "@/test/render";
import { AppShell } from "./AppShell";

vi.mock("@/lib/auth", () => ({
  useAuth: vi.fn(() => ({ status: "unauthenticated", user: null })),
}));

describe("AppShell", () => {
  it("renders Header, the children inside <main>, and Footer in order", () => {
    renderWithProviders(
      <AppShell>
        <div data-testid="page-content">page</div>
      </AppShell>
    );
    expect(screen.getByRole("link", { name: "SLPA" })).toBeInTheDocument(); // Header
    expect(screen.getByTestId("page-content")).toBeInTheDocument();          // children inside main
    expect(screen.getByText(/SLPA. Not affiliated/)).toBeInTheDocument();    // Footer
  });
});
```

- [ ] **Step 2: Run the test, expect FAIL**

```bash
npm run test -- AppShell
```

Expected: FAIL — `Cannot find module './AppShell'`.

- [ ] **Step 3: Write the implementation**

```tsx
// frontend/src/components/layout/AppShell.tsx
import { Header } from "./Header";
import { Footer } from "./Footer";

export function AppShell({ children }: { children: React.ReactNode }) {
  return (
    <div className="flex min-h-screen flex-col">
      <Header />
      <main className="flex-1">{children}</main>
      <Footer />
    </div>
  );
}
```

- [ ] **Step 4: Modify `RootLayout` to wrap children in `<AppShell>`**

Open `frontend/src/app/layout.tsx`. Replace `<Providers>{children}</Providers>` with `<Providers><AppShell>{children}</AppShell></Providers>` and add the import:

```tsx
import { AppShell } from "@/components/layout/AppShell";

// ... (rest of file unchanged)

export default function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <html lang="en" className={manrope.variable} suppressHydrationWarning>
      <body className="min-h-screen font-sans bg-surface text-on-surface antialiased">
        <Providers>
          <AppShell>{children}</AppShell>
        </Providers>
      </body>
    </html>
  );
}
```

- [ ] **Step 5: Run the test, expect PASS**

```bash
npm run test -- AppShell
```

Expected: 1 passed.

- [ ] **Step 6: Run the dev server and visually verify the shell**

```bash
npm run dev
```

Open `http://localhost:3000`. Expected:
- Header at the top with SLPA wordmark, nav links, theme toggle, notification bell, Sign in / Register buttons
- The existing landing placeholder content in the middle
- Footer at the bottom with About / Terms / Contact / Partners links and a "© 2026 SLPA. Not affiliated with Linden Lab." copyright

Stop the dev server.

- [ ] **Step 7: Commit**

```bash
git add src/components/layout/AppShell.tsx src/components/layout/AppShell.test.tsx src/app/layout.tsx
git commit -m "feat(layout): wire AppShell into RootLayout"
```

---

## Phase F — Routing (Task 26)

### Task 26: All eleven placeholder pages

**Files:**
- Rewrite: `frontend/src/app/page.tsx` (replace minimal placeholder with `<PageHeader />`)
- Create: `frontend/src/app/browse/page.tsx`
- Create: `frontend/src/app/auction/[id]/page.tsx`
- Create: `frontend/src/app/dashboard/page.tsx`
- Create: `frontend/src/app/login/page.tsx`
- Create: `frontend/src/app/register/page.tsx`
- Create: `frontend/src/app/forgot-password/page.tsx`
- Create: `frontend/src/app/about/page.tsx`
- Create: `frontend/src/app/terms/page.tsx`
- Create: `frontend/src/app/contact/page.tsx`
- Create: `frontend/src/app/partners/page.tsx`

No tests for individual placeholder pages — they're all the same shape (RSC + `<PageHeader />`) and testing them is testing PageHeader 11 times. The smoke test (Task 29) verifies routing end-to-end.

- [ ] **Step 1: Rewrite the landing page**

```tsx
// frontend/src/app/page.tsx
import { PageHeader } from "@/components/layout/PageHeader";

export default function HomePage() {
  return (
    <PageHeader
      title="SLPA"
      subtitle="Player-to-player land auctions for Second Life — coming soon."
    />
  );
}
```

- [ ] **Step 2: Create the eight static placeholder pages**

```tsx
// frontend/src/app/browse/page.tsx
import type { Metadata } from "next";
import { PageHeader } from "@/components/layout/PageHeader";

export const metadata: Metadata = { title: "Browse Auctions" };

export default function BrowsePage() {
  return (
    <PageHeader
      title="Browse Auctions"
      subtitle="Active land listings across the grid."
    />
  );
}
```

```tsx
// frontend/src/app/dashboard/page.tsx
import type { Metadata } from "next";
import { PageHeader } from "@/components/layout/PageHeader";

export const metadata: Metadata = { title: "Dashboard" };

export default function DashboardPage() {
  return (
    <PageHeader
      title="Dashboard"
      subtitle="Your bids, listings, and sales."
    />
  );
}
```

```tsx
// frontend/src/app/login/page.tsx
import type { Metadata } from "next";
import { PageHeader } from "@/components/layout/PageHeader";

export const metadata: Metadata = { title: "Sign In" };

export default function LoginPage() {
  return <PageHeader title="Sign In" subtitle="Welcome back to SLPA." />;
}
```

```tsx
// frontend/src/app/register/page.tsx
import type { Metadata } from "next";
import { PageHeader } from "@/components/layout/PageHeader";

export const metadata: Metadata = { title: "Register" };

export default function RegisterPage() {
  return <PageHeader title="Register" subtitle="Create your SLPA account." />;
}
```

```tsx
// frontend/src/app/forgot-password/page.tsx
import type { Metadata } from "next";
import { PageHeader } from "@/components/layout/PageHeader";

export const metadata: Metadata = { title: "Forgot Password" };

export default function ForgotPasswordPage() {
  return (
    <PageHeader
      title="Forgot Password"
      subtitle="We'll send you a reset link."
    />
  );
}
```

```tsx
// frontend/src/app/about/page.tsx
import type { Metadata } from "next";
import { PageHeader } from "@/components/layout/PageHeader";

export const metadata: Metadata = { title: "About" };

export default function AboutPage() {
  return <PageHeader title="About SLPA" subtitle="The story behind the auctions." />;
}
```

```tsx
// frontend/src/app/terms/page.tsx
import type { Metadata } from "next";
import { PageHeader } from "@/components/layout/PageHeader";

export const metadata: Metadata = { title: "Terms" };

export default function TermsPage() {
  return <PageHeader title="Terms of Service" subtitle="The rules of the road." />;
}
```

```tsx
// frontend/src/app/contact/page.tsx
import type { Metadata } from "next";
import { PageHeader } from "@/components/layout/PageHeader";

export const metadata: Metadata = { title: "Contact" };

export default function ContactPage() {
  return <PageHeader title="Contact" subtitle="Get in touch with the SLPA team." />;
}
```

```tsx
// frontend/src/app/partners/page.tsx
import type { Metadata } from "next";
import { PageHeader } from "@/components/layout/PageHeader";

export const metadata: Metadata = { title: "Partners" };

export default function PartnersPage() {
  return <PageHeader title="Partners" subtitle="Our verification and bot service partners." />;
}
```

- [ ] **Step 3: Create the dynamic auction route**

```tsx
// frontend/src/app/auction/[id]/page.tsx
import type { Metadata } from "next";
import { PageHeader } from "@/components/layout/PageHeader";

export const metadata: Metadata = { title: "Auction" };

export default async function AuctionPage({
  params,
}: {
  params: Promise<{ id: string }>;
}) {
  const { id } = await params;
  return (
    <PageHeader
      title={`Auction #${id}`}
      breadcrumbs={[
        { label: "Browse", href: "/browse" },
        { label: `Auction #${id}` },
      ]}
    />
  );
}
```

The `params` is a `Promise` in Next 16 — `await` it before destructuring (Important Context #8).

- [ ] **Step 4: Run the build to confirm all routes compile**

```bash
npm run build
```

Expected: `Compiled successfully`. The output should list all eleven routes (`/`, `/browse`, `/auction/[id]`, `/dashboard`, `/login`, `/register`, `/forgot-password`, `/about`, `/terms`, `/contact`, `/partners`).

- [ ] **Step 5: Commit**

```bash
git add src/app/page.tsx src/app/browse src/app/auction src/app/dashboard src/app/login src/app/register src/app/forgot-password src/app/about src/app/terms src/app/contact src/app/partners
git commit -m "feat(routing): add eleven placeholder pages wired through PageHeader and AppShell"
```

---

## Phase G — Verification + cleanup (Tasks 27–29)

### Task 27: `verify-coverage.sh` + run all verify rules

**Files:**
- Create: `frontend/scripts/verify-coverage.sh`

- [ ] **Step 1: Create the directory and script**

```bash
cd frontend
mkdir -p scripts
```

```bash
# frontend/scripts/verify-coverage.sh
#!/usr/bin/env bash
# Fails (exit 1) if any src/components/ui/*.tsx is missing a sibling .test.tsx.
# Excludes barrel files (index, icons) which are not testable on their own.

set -euo pipefail

missing=0
for f in src/components/ui/*.tsx; do
  base="$(basename "$f" .tsx)"
  case "$base" in
    index|icons) continue ;;
  esac
  if [[ ! -f "src/components/ui/${base}.test.tsx" ]]; then
    echo "MISSING TEST: $f"
    missing=1
  fi
done

if [[ "$missing" -eq 1 ]]; then
  exit 1
fi
echo "All UI primitives have sibling test files."
```

- [ ] **Step 2: Make the script executable**

```bash
chmod +x scripts/verify-coverage.sh
git update-index --chmod=+x scripts/verify-coverage.sh
```

The `git update-index --chmod=+x` ensures the executable bit is recorded in git on Windows.

- [ ] **Step 3: Run all five verify checks**

```bash
npm run verify
```

Expected: each step completes with no output (or "All UI primitives have sibling test files." for the coverage check), and the overall command exits 0. If any check fails:

- `verify:no-dark-variants` failure → grep `dark:` in `src/components` and `src/app`, replace each occurrence with a token-driven approach.
- `verify:no-hex-colors` failure → find the hex inside a `className` or `style` attribute and replace with a token utility.
- `verify:no-inline-styles` failure → move the inline styles into a `className`.
- `verify:coverage` failure → the script will print `MISSING TEST: <file>`. Add the missing test file.

- [ ] **Step 4: Run the full test suite as a regression check**

```bash
npm run test
```

Expected: ~64 cases passing (sum from spec §9.4). Exact count: 5 lib (api) + 1 (auth) + 2 (cn) + 1 (smoke) + 6 (Button) + 4 (IconButton) + 5 (Input) + 3 (Card) + 5 (StatusBadge) + 4 (Avatar) + 3 (ThemeToggle) + 5 (Dropdown) + 4 (NavLink) + 5 (PageHeader) + 2 (Footer) + 4 (MobileMenu) + 5 (Header) + 1 (AppShell) = **65 cases** including the smoke test.

- [ ] **Step 5: Run lint and build as final regression checks**

```bash
npm run lint
npm run build
```

Both expected: clean.

- [ ] **Step 6: Commit**

```bash
git add scripts/verify-coverage.sh
git commit -m "chore(frontend): add verify-coverage.sh script for primitive test coverage"
```

---

### Task 28: README sweep (root README frontend section)

**Files:**
- Modify: `README.md` (project root) — frontend section update

- [ ] **Step 1: Read the current README to find the frontend section**

```bash
cd /c/Users/heath/Repos/Personal/slpa
grep -n "Frontend\|frontend" README.md | head -10
```

Locate the existing frontend-related content in the "Local development without Docker" or "Running tests" sections.

- [ ] **Step 2: Update the "Running tests" section**

Find:

```markdown
cd backend && ./mvnw test             # unit, slice, and integration tests (integration tests need postgres on :5432)
cd frontend && npm run lint           # frontend unit tests (Vitest) land in Task 02-04
```

Replace with:

```markdown
cd backend && ./mvnw test             # unit, slice, and integration tests (integration tests need postgres on :5432)
cd frontend && npm run test           # vitest unit tests (~65 cases — primitives, layout, lib)
cd frontend && npm run lint           # eslint
cd frontend && npm run verify         # grep-based rules: no dark: variants, no hex colors, no inline styles, every primitive has a test
```

- [ ] **Step 3: Update the "Local development without Docker" frontend block**

Find the existing block:

```markdown
# Frontend
cd frontend
npm install
npm run dev
```

It already shows `npm run dev`. Add a sentence underneath it:

```markdown
The frontend dev server runs at `http://localhost:3000`. Component primitives live under `src/components/ui/` (Button, IconButton, Input, Card, StatusBadge, Avatar, ThemeToggle, Dropdown), layout shell under `src/components/layout/`, and the typed API client + auth stub + cn helper under `src/lib/`. Theme tokens (M3 Material Design vocabulary, both light and dark) live in `src/app/globals.css`. The full design rationale is in [`docs/superpowers/specs/2026-04-10-task-01-06-frontend-foundation-design.md`](docs/superpowers/specs/2026-04-10-task-01-06-frontend-foundation-design.md).
```

- [ ] **Step 4: Verify the README still renders correctly**

```bash
head -100 README.md
```

Spot-check that the sections flow correctly and no markdown is broken.

- [ ] **Step 5: Commit**

```bash
git add README.md
git commit -m "docs(readme): document frontend test/verify commands and component library shape"
```

---

### Task 29: Final smoke test + dev walkthrough

**Files:**
- None — this is a manual verification task. Paste the smoke test results into the PR description per spec §10.3.

- [ ] **Step 1: Run the dev server**

```bash
cd frontend
npm run dev
```

- [ ] **Step 2: Walk through the smoke test checklist (paste this into the PR description)**

```
[ ] Open http://localhost:3000 — landing placeholder renders with "SLPA" wordmark
[ ] Confirm dark mode is the default on first visit, no theme flash on initial paint
[ ] Toggle to light mode via header toggle
[ ] Refresh — light mode persists
[ ] Click Browse in desktop nav — routes to /browse, PageHeader renders
[ ] Click Dashboard — routes to /dashboard
[ ] Click Create Listing — routes to /auction/new (placeholder)
[ ] Click each footer link (About, Terms, Contact, Partners) — each routes
[ ] Open /auction/42 directly — breadcrumbs render with Browse → Auction #42
[ ] Resize viewport below md (768px) — hamburger appears
[ ] Click hamburger — drawer slides in, focus trapped
[ ] Press Escape — drawer closes, focus returns to hamburger
[ ] Open drawer again, click a nav link — drawer closes and route changes
[ ] Open browser dev tools → Application → Local Storage — confirm `theme` key persists
[ ] Repeat all steps in light mode
```

Tick each box as you verify it. If any step fails, stop and fix the underlying issue before continuing.

- [ ] **Step 3: Run the final automated checks**

```bash
npm run test     # all ~65 cases pass
npm run lint     # no errors
npm run build    # no warnings
npm run verify   # all five rules pass
```

All four must pass before marking the task done.

- [ ] **Step 4: Stop the dev server and confirm clean working tree**

Stop the dev server with Ctrl+C. Then:

```bash
cd /c/Users/heath/Repos/Personal/slpa
git status
```

Expected: clean working tree (everything has been committed task-by-task).

- [ ] **Step 5: This task has no commit** — the smoke test results live in the PR description.

---

## Self-review checklist

After implementation but before requesting review:

1. **Spec coverage** — every section of [`spec §3 through §12`](../specs/2026-04-10-task-01-06-frontend-foundation-design.md) maps to a task in this plan. Cross-check the file inventory in spec §3.1 against the plan's File Structure table — every file is owned by a task.
2. **Test count** — `npm run test` shows ~65 cases (the spec table sums to 64 + 1 smoke test = 65). If the count is materially different, find which task didn't ship its tests.
3. **Verify rules** — `npm run verify` exits 0 with no output. If any rule fails, the corresponding component has a violation; fix at the source, don't suppress the rule.
4. **Manual smoke** — every checkbox in Task 29's smoke test list is ticked in both modes.
5. **README sweep** — `git log --oneline README.md` shows the docs commit from Task 28.
6. **No `dark:` strings, no hex colors in components** — rerun the grep commands manually to be sure:
   ```bash
   grep -rn "dark:" frontend/src/components frontend/src/app
   grep -rEn '#[0-9a-fA-F]{3,8}' frontend/src/components frontend/src/app
   ```
   Both should return zero matches (the only hex values in the codebase live in `globals.css`).

If anything is off, fix it and re-run the relevant verify command. The plan is done when all five checks pass, the smoke test is green, and the task PR is opened with the smoke test results in the description.
