# Footgun Ledger

A running catalog of footguns, gotchas, and "the plan didn't say but the implementation will fail without this" lessons. **Every implementer prompt should reference the relevant sections before writing code.** Every reviewer catch becomes a ledger entry before the next task is dispatched.

This ledger compounds across tasks: Task N benefits from every footgun caught in Tasks 1 through N-1. Read the section headers to find what's relevant; skim the entries; apply.

Organized by category. Within each category, entries are short (rule + why + how to apply).

---

## §1 Shell / cross-platform

### 1.1 `npm run` scripts execute via `cmd.exe` on Windows, not bash

**Why:** npm on `win32` always spawns scripts through `cmd.exe /d /s /c <script>` regardless of which terminal you launched npm from. Git Bash, PowerShell, WSL — they all hit `cmd.exe` for npm scripts. This is hard-coded in `@npmcli/promise-spawn`.

**How to apply:** Any npm script that uses bash syntax (`!` pipeline negation, `$(...)` command substitution, `[[ ... ]]` tests, brace expansion, here-docs) **will fail on Windows** with `'!' is not recognized` or similar. Three options:

1. **Move logic into `frontend/scripts/*.sh` files** invoked via `bash scripts/foo.sh` from `package.json`. Cleanest. Mirrors the existing `verify-coverage.sh` pattern. Use this for anything beyond a one-liner.
2. **Wrap inline in `bash -c "..."`** with JSON-escaped double quotes around the bash command and single quotes around shell arguments. Use for one-liners only — quoting gets ugly fast.
3. **Use cross-platform Node scripts** via tools like `cross-env` / `shx` / a small `scripts/*.mjs`. Heavier dependency, useful when bash isn't available at all (e.g., minimal CI containers without git).

**Verification command after wiring:** Run the script through `npm run <name>` and confirm it actually executes bash, not falls through to cmd.exe with a syntax error.

### 1.2 `! grep ...` masks grep's exit code 2 (directory not found)

**Why:** POSIX pipeline negation (`!`) inverts ANY non-zero exit to 0. Grep returns:
- exit 0 if matches found
- exit 1 if no matches
- exit 2 if a target directory doesn't exist (or other error)

`! grep ...` against a missing directory returns exit 0 — a **silent false pass**. The script reports green when violations are present elsewhere.

**How to apply:** Never use `! grep` against directories that might not exist yet. Instead:

```bash
set -uo pipefail

dirs=()
[[ -d src/components ]] && dirs+=(src/components)
[[ -d src/app ]] && dirs+=(src/app)

if [[ ${#dirs[@]} -eq 0 ]]; then
  echo "skip: no target dirs exist yet"
  exit 0
fi

if grep -rEn 'pattern' "${dirs[@]}"; then
  echo "FAIL: violations above"
  exit 1
fi
exit 0
```

The `if grep ...; then exit 1; fi` pattern uses grep's exit codes naturally without `!` inversion. Grep exit 2 is unreachable because the directory existence guard runs first.

### 1.3 Shell scripts need executable bits recorded in git

**Why:** `chmod +x` on Windows changes the local filesystem mode but git on Windows doesn't always pick it up. The next clone/checkout won't have the exec bit and `bash scripts/foo.sh` works only because the wrapper command name is `bash`, not because the script itself is executable.

**How to apply:** After creating any new shell script:

```bash
chmod +x scripts/foo.sh
git update-index --add --chmod=+x scripts/foo.sh
```

The `git update-index --chmod=+x` explicitly sets the mode bit in the git index. Verify with `git ls-files -s scripts/` — should show `100755`, not `100644`.

### 1.4 npm scripts run with cwd = package directory

**Why:** When a developer runs `cd frontend && npm run verify`, the script's `pwd` is `frontend/`. When they run `bash scripts/verify-no-X.sh` from anywhere else, `pwd` is wherever they happened to be standing.

**How to apply:** Either (a) document at the top of the script that it must be run from `frontend/`, or (b) make the script location-independent with `cd "$(dirname "$0")/.."`. For npm-script-only scripts, (a) is fine — the npm wrapper enforces cwd. For scripts a human might run directly, (b).

### 1.5 `for f in src/components/ui/*.tsx` glob also matches `*.test.tsx` siblings

**Why:** Bash's `*.tsx` glob is naive — it matches every file ending in `.tsx`, including the sibling test files (`Foo.test.tsx`). Code that strips `.tsx` with `basename "$f" .tsx` then yields `Foo.test`, not `Foo`, because `basename` only strips a literal suffix from the very end. The next line — `[[ ! -f "src/components/ui/${base}.test.tsx" ]]` — looks for `Foo.test.test.tsx`, which doesn't exist, and reports a false `MISSING TEST` for every test file in the directory.

The canonical `verify-coverage.sh` from plan Task 27 had exactly this bug. The implementer caught it during the post-write smoke run (every primitive reported missing) and patched the case-statement skip list.

**How to apply:** When iterating `*.tsx` and pairing source files to test files, exclude the test files from the iteration:

```bash
for f in src/components/ui/*.tsx; do
  base="$(basename "$f" .tsx)"
  case "$base" in
    index|icons|*.test) continue ;;
  esac
  if [[ ! -f "src/components/ui/${base}.test.tsx" ]]; then
    echo "MISSING TEST: $f"
    missing=1
  fi
done
```

The `*.test)` arm in the case statement skips any base name ending in `.test` (i.e., the basename of a `Foo.test.tsx` file after `basename .tsx`). Alternative: use a more specific glob like `find src/components/ui -name '*.tsx' -not -name '*.test.tsx'`. Either works; the case-statement form matches the existing project style.

**Caught at implementation time in Task 27.** The canonical plan snippet shipped without this exclusion. Single-line fix; the implementer flagged it as DONE_WITH_CONCERNS so the deviation from canonical was visible to the reviewer rather than buried.

**General rule:** any shell loop that pairs a source file to a sibling test file via `basename` must exclude the test file from the source iteration, OR must assert that the basename doesn't already contain `.test`. Don't trust `*.tsx` to mean "implementation files."

---

## §2 Runtime / tooling

### 2.1 Vitest does NOT read `tsconfig.json` paths

**Why:** Vitest uses its own resolver (Vite's, technically). The TypeScript compiler reads `tsconfig.json` for path aliases; Vitest doesn't.

**How to apply:** Any `@/*` alias declared in `tsconfig.json` `paths` MUST also be declared in `vitest.config.ts` `resolve.alias`:

```ts
// vitest.config.ts
import { fileURLToPath } from "url";

export default defineConfig({
  resolve: {
    alias: {
      "@": fileURLToPath(new URL("./src", import.meta.url)),
    },
  },
  // ...
});
```

Without this mirror, every test that imports from `@/components/...` fails with `Cannot find module`.

### 2.2 `next/font/google` blows up jsdom

**Why:** `next/font/google` only resolves inside the Next build pipeline. Test files that transitively import `app/layout.tsx` (or any module that imports Manrope, Inter, Geist, etc.) crash on import with "Module not found" or a font-loading error.

**How to apply:** Mock globally in `vitest.setup.ts`:

```ts
vi.mock("next/font/google", () => ({
  Manrope: () => ({
    className: "font-manrope",
    variable: "--font-manrope",
  }),
}));
```

Add an entry per font your project uses. The shape returned matches what the real `next/font/google` returns: `{ className, variable }`.

### 2.3 `next/navigation` hooks need a request context

**Why:** `usePathname`, `useRouter`, `useSearchParams` from `next/navigation` only work inside a real Next request context. In jsdom, they throw "invariant" errors on first call.

**How to apply:** Mock globally in `vitest.setup.ts`:

```ts
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
```

Tests that need a specific pathname override per-test inside `beforeEach` (see §4.3). Don't re-mock at the file level — the global mock is shared.

### 2.4 `QueryClient` must be inside `useState`, not module-level

**Why:** Module-level `const queryClient = new QueryClient(...)` shares state across requests on the server. Constructing inside `useState(() => new QueryClient(...))` creates a fresh client per component instance, which on the server is once per request and on the client is once per session.

**How to apply:**

```tsx
"use client";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { useState } from "react";

export function QueryProvider({ children }) {
  const [queryClient] = useState(() => new QueryClient({
    defaultOptions: { queries: { staleTime: 60_000, refetchOnWindowFocus: false, retry: 1 } },
  }));
  return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
}
```

The component itself must be `"use client"` because `useState` is a hook.

### 2.5 Vitest version skew

**Status:** vitest 4 is installed, the plan was written assuming vitest 3. Verified by code reviewer: vitest 4's config shape is identical for `environment`, `setupFiles`, `globals`, `css`, `include`, `exclude`. No migration needed for the plan's existing config snippets.

**Action item:** After writing `vitest.config.ts` and `vitest.setup.ts`, run `npm run test` once as a live smoke check to confirm the config parses and the renderWithProviders smoke test passes.

### 2.6 jsdom does not implement `window.matchMedia`

**Why:** jsdom ships without a `matchMedia` implementation. `next-themes` calls `window.matchMedia` inside a `useEffect` to detect the system color-scheme preference. Any test that renders a `ThemeProvider` (including via `renderWithProviders`) crashes with `TypeError: window.matchMedia is not a function`.

**How to apply:** Stub it in `vitest.setup.ts` before any test runs:

```ts
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
```

Place this after the imports but before the `vi.mock(...)` blocks. The stub always returns `matches: false`, which is deterministic — it prevents nondeterminism from an actual system dark-mode preference leaking into tests (consistent with `enableSystem={false}` in `renderWithProviders`).

**`configurable: true` matters.** Without it, `Object.defineProperty` creates a non-configurable property and `vi.spyOn(window, "matchMedia", ...)` throws `TypeError: Cannot redefine property: matchMedia`. Reassignment via `window.matchMedia = vi.fn(...)` still works because `writable: true`, but spies don't. Future tests that need per-test override of matchMedia behavior — e.g., simulating `prefers-reduced-motion` — depend on this. Cheap to set, expensive to debug later.

**Caught at implementation time in Task 4** by the implementer reading FOOTGUNS §2.1 (jsdom stubs) and proactively checking related browser APIs before running the smoke test for the first time. The pattern that finds these is "the ledger trains you to ask 'what else is jsdom missing?' before the test runs." When a future implementer reads this entry, the lesson is: **implementers who read this catch things.**

### 2.7 jsdom does not implement `ResizeObserver` — Headless UI v2 needs it

**Why:** jsdom ships without a `ResizeObserver` implementation. Headless UI v2's `<Menu>`, `<Dialog>`, `<Listbox>`, and `<Combobox>` primitives all use `@floating-ui/react` internally for panel positioning, and `@floating-ui/react` calls `new ResizeObserver(...)` on mount. Without a stub, any test that opens a Headless UI panel crashes with `ReferenceError: ResizeObserver is not defined`. The crash happens AFTER the test assertion runs, so individual tests may appear to pass while the test runner reports unhandled errors.

**How to apply:** Stub it as a no-op class in `vitest.setup.ts`, before the other jsdom shims:

```ts
global.ResizeObserver = class ResizeObserver {
  observe() {}
  unobserve() {}
  disconnect() {}
};
```

The no-op is sufficient because positioning isn't exercised in jsdom — there's no real layout, no real coordinates, no real resize events. Headless UI just needs the class to exist so its `new ResizeObserver(...)` call doesn't throw. Real positioning logic only matters in the browser, where `ResizeObserver` is built in.

**Caught at implementation time in Task 18** (Dropdown — first Headless UI primitive). The same gap will bite **Task 23 (MobileMenu, uses Headless UI `<Dialog>`)** if the stub is removed, and any future task that uses `<Listbox>`, `<Combobox>`, or `<Disclosure>`. The stub is one-time and protects all current and future Headless UI consumers.

**General pattern**: any third-party React library that uses `@floating-ui/react` for positioning (Headless UI, Radix UI, Floating UI directly) will need this stub in jsdom-based test environments. If a future task imports such a library, the stub is already in place — no action needed.



---

## §3 Language / framework

### 3.1 Next.js 16: dynamic-route `params` is a Promise

**Why:** Next.js 15 introduced this; Next.js 16 keeps it. `params` and `searchParams` props passed to dynamic-route page components are `Promise<{...}>`, not the destructured object directly.

**How to apply:**

```tsx
// src/app/auction/[id]/page.tsx
export default async function AuctionPage({
  params,
}: {
  params: Promise<{ id: string }>;
}) {
  const { id } = await params;
  return <PageHeader title={`Auction #${id}`} />;
}
```

Trying to destructure `{ params: { id } }` directly produces a runtime error. The page function must be `async` to allow the `await`.

### 3.2 Tailwind 4: `@variant dark (.dark &);` is deliberately omitted

**Why:** The project rule is "zero `dark:` variants in `src/components` and `src/app`." If the directive exists, `dark:bg-red-500` compiles to a working class and a single PR slipping through review puts a crack in the wall. Without the directive, `dark:bg-red-500` is parseable Tailwind syntax but produces no CSS — the class silently no-ops, and the developer notices dark mode looks wrong by failing to override it. **The failure teaches the rule.**

**How to apply:** `globals.css` must NOT contain `@variant dark (.dark &);`. If you see it in any file in `src/`, remove it. The verify rule `verify:no-dark-variants` enforces the absence of `dark:` strings in component code; `@variant dark` would let those strings compile.

### 3.3 `forcedTheme` is off-label as a test default

**Why:** `forcedTheme` from `next-themes` is designed for "this page is always light mode" edge cases (documentation sites). Using it as a default in tests breaks the one test that legitimately needs to observe a theme transition (the `ThemeToggle` integration test must verify clicking the button flips `documentElement`'s class).

**How to apply:** `renderWithProviders` accepts `theme?: "light" | "dark"` (default `"light"`) AND an opt-in `forceTheme?: boolean` (default `false`). The default path uses `defaultTheme={theme}` + `enableSystem={false}` so `next-themes` can actually transition. Tests that need a hard lock pass `forceTheme: true` explicitly. The `enableSystem={false}` prevents nondeterminism from jsdom's stub `matchMedia`.

### 3.4 React 19: `act` is exported from `react`, not `react-dom/test-utils`

**Why:** React 18.3+ promoted `act` to the `react` package. React 19 keeps it there.

**How to apply:**

```tsx
import { act } from "react"; // ✅ correct
// not: import { act } from "react-dom/test-utils"; ❌
```

Some older test examples on the internet still show the `react-dom/test-utils` form. Don't.

### 3.5 `eslint-plugin-react-hooks` ships two experimental rules that bite common patterns

**Why:** Recent versions of `eslint-plugin-react-hooks` (transitively required by `eslint-config-next`) enable two new rules by default that didn't exist in older versions:

- `react-hooks/set-state-in-effect` — flags `setState(...)` calls inside the body of a `useEffect`. Triggered by the canonical "hydration mount" pattern (`useEffect(() => { setMounted(true); }, []);`) used by `next-themes` consumers and any other "wait for client-side mount before rendering" trick.
- `react-hooks/immutability` — flags any mutation of a variable defined outside the component or hook. Triggered by the test pattern of capturing values into an outer-scope object (`captured.current = client;`) — common when verifying React behavior across rerenders. The autofix hint is "rename to end in `Ref`," which doesn't actually silence the rule on its own; you also need a disable comment.

These rules are not in the `eslint-config-next` ruleset by name — they ride along when `eslint-plugin-react-hooks` is upgraded (via `npm install` of any related package). The two violations in this codebase had been latent since Tasks 4 and 17 because no implementer prompt ran `npm run lint` as a hard gate; the verify chain runs four shell scripts but does NOT include `eslint`. Task 27's protocol surfaced both errors at once.

**How to apply:**
- For the hydration-mount pattern (`setMounted(true)` in a `useEffect`), suppress with `// eslint-disable-line react-hooks/set-state-in-effect` on the offending line. The pattern is intentional and idiomatic for `next-themes` SSR safety; the rule's "you might not need an effect" guidance doesn't apply.
- For test-side outer-variable mutation, the conventional fix is rename-to-`Ref` (e.g., `captured` → `capturedRef`) AND add `// eslint-disable-next-line react-hooks/immutability` above the mutation. The rename alone doesn't satisfy the rule because the underlying check is on the assignment, not the name. Both moves are required.
- **Run `npm run lint` as a regression check after any task that touches a component using effects or any test that captures values from a render probe.** The verify chain should have been augmented to call lint; that's a Task 28+ followup if anyone notices.

**Caught at implementation time in Task 27** when the canonical task protocol required `npm run lint` to be green and the implementer surfaced two latent violations from much earlier tasks.

---

## §4 Test discipline (project-specific)

### 4.1 Every test imports from `@/test/render`, never raw `@testing-library/react`

**Why:** The first test that imports `render` directly from `@testing-library/react` will get a confusing `useTheme` error when its component tree hits the first hook, because no `ThemeProvider` is wrapping the rendered tree.

**How to apply:** All test files import `renderWithProviders` (and any RTL utilities they need) from `@/test/render`. The render helper re-exports `screen`, `within`, `fireEvent`, `waitFor`, and `userEvent` so test files have one import line:

```ts
import { renderWithProviders, screen, userEvent } from "@/test/render";
```

### 4.2 `cn(base, variant, className)` in every primitive

**Why:** Without `tailwind-merge`, passing `className="p-8"` to a primitive whose base classes include `p-4` produces `"p-4 p-8"` and Tailwind resolves to whichever rule comes last in the generated CSS. `tailwind-merge` dedupes intelligently so consumer classes always win conflicts.

**How to apply:** Every primitive that accepts a consumer `className` prop merges via `cn(baseClasses, variantClasses, className)` from `@/lib/cn`. Skipping `cn()` is a regression. Every primitive's "renders with correct base classes" test must include an assertion that consumer `className` wins via merge — fold into the existing test, don't add a separate case.

### 4.3 Per-test mock reset for global mocks

**Why:** `vi.mock("next/navigation", ...)` lives in `vitest.setup.ts`. When a test overrides via `vi.mocked(usePathname).mockReturnValue("/browse")`, that override persists into the next test unless reset. Subtle leaks → flaky tests weeks later.

**The underlying mock factory MUST use `vi.fn()`, not a plain arrow function.** `vi.mocked(...)` returns a typed spy interface that exposes `.mockReset()`, `.mockReturnValue()`, etc. — but only if the underlying export is actually a vitest spy. If the global mock factory in `vitest.setup.ts` returns a plain function:

```ts
// WRONG — vi.mocked(usePathname).mockReset() throws TypeError
vi.mock("next/navigation", () => ({
  usePathname: () => "/",
  // ...
}));
```

…then `vi.mocked(usePathname).mockReset()` throws `TypeError: mockReset is not a function` because the export is just a function, not a spy. The fix is to wrap the factory in `vi.fn()`:

```ts
// RIGHT — vi.fn() returns a spy that vi.mocked() can manipulate
vi.mock("next/navigation", () => ({
  usePathname: vi.fn(() => "/"),
  // ...
}));
```

**Caught at implementation time in Task 20** (NavLink — first task to exercise per-test `usePathname` overrides). Tasks 4-19 mocked `next/navigation` with plain functions and didn't notice because no test called `.mockReturnValue()` on them. The first per-test override surfaced the gap.

**General rule:** any global mock in `vitest.setup.ts` whose export will be overridden per-test must use `vi.fn(implementation)`, not just `implementation`. If the test only ever uses the default value, a plain function works — but adding the `vi.fn()` wrapper as a habit is cheap insurance against the next per-test override that surfaces.

**How to apply (consumer side):**

```ts
import { usePathname } from "next/navigation";
import { vi, beforeEach } from "vitest";

const mockedUsePathname = vi.mocked(usePathname);

describe("NavLink", () => {
  beforeEach(() => {
    mockedUsePathname.mockReset();
    mockedUsePathname.mockReturnValue("/");
  });
  // tests...
});
```

### 4.4 `aria-label` required in TS type for `IconButton`

**Why:** Compile error > lint warning > review catch. The 20% of icon-only buttons that ship without an accessible name slip through review.

**How to apply:** `IconButtonProps` declares `"aria-label": string` (not optional). Same principle: `Avatar.alt` is required. Apply to any future image-wrapping primitive.

### 4.5 Date-dependent tests must pin time

**Why:** Tests that assert on `new Date().getFullYear()` or similar can desync at the year/day/hour boundary if the test runner happens to straddle the rollover. Vanishingly rare in practice, deterministically zero with the stub.

**How to apply:**

```ts
beforeEach(() => {
  vi.useFakeTimers({ now: new Date("2026-06-15T12:00:00Z") });
});
afterEach(() => {
  vi.useRealTimers();
});
```

The Footer copyright-year test is the canonical example.

### 4.6 Fresh `QueryClient` per test inside the render helper

**Why:** Sharing one `QueryClient` across tests means one test's cached queries leak into the next, surfacing as flaky failures weeks later. The `renderWithProviders` factory must construct a fresh client every call, with `retry: false` on both queries and mutations (we don't want 3× retries turning a 10ms test into a 3-second timeout).

**How to apply:** Inside `makeWrapper` (the inner factory), construct `new QueryClient(...)` per call. Don't optimize this into a singleton. Test runtime is fast enough; correctness wins.

### 4.7 `QueryClient` re-instantiates on every wrapper re-render (no `useRef`)

**Why:** The current `renderWithProviders` constructs the `QueryClient` inside the `Wrapper` function body, not inside a `useRef`. Every time React re-renders the Wrapper — which happens on RTL's `rerender()` or any state update that bubbles back to the wrapper — a brand-new `QueryClient` replaces the previous one and **destroys any accumulated cache state**. The "fresh per test" guarantee from §4.6 still holds at the test boundary, but "stable within a test" does not.

**Current impact: zero** through Task 25, because TanStack Query is wired-but-unused in this plan. None of the primitive or layout tests inspect cache state. The smoke test, the lib tests, and the component tests don't call `rerender()` against query-backed UI.

**Future impact:** if a test ever calls `rerender()` after a query resolves (e.g., a component test that mounts a `useQuery` consumer, waits for the response, then re-renders to test prop changes), the second render gets a fresh client with an empty cache and the test sees the loading state again.

**How to apply when it becomes relevant:** Stabilize the client with `useRef`:

```tsx
import { useRef } from "react";

return function Wrapper({ children }: { children: ReactNode }) {
  const queryClientRef = useRef<QueryClient | null>(null);
  if (!queryClientRef.current) {
    queryClientRef.current = new QueryClient({
      defaultOptions: {
        queries: { retry: false },
        mutations: { retry: false },
      },
    });
  }
  return (
    <ThemeProvider attribute="class" defaultTheme={theme} enableSystem={false} forcedTheme={force ? theme : undefined}>
      <QueryClientProvider client={queryClientRef.current}>
        {children}
      </QueryClientProvider>
    </ThemeProvider>
  );
};
```

**Action item — REQUIRED deliverable for Task 8 (not optional cleanup):**

Task 8 (`lib/api.ts`) MUST replace the current unstabilized `QueryClient` construction in `src/test/render.tsx` with the `useRef` pattern shown above. This is a hard requirement, not a "fix it if you have time" item, because Task 8 is the first task whose tests could plausibly drive a query through the React Query layer (even if `lib/api.test.ts` itself only mocks `fetch`, the helper has to be ready for the next task that does).

**Mechanically verifiable by a stability test in Task 8.** The Task 8 implementer adds a test case to `src/test/render.smoke.test.tsx` that:

1. Constructs a small probe component that captures the `QueryClient` instance from `useQueryClient()` in a ref.
2. Renders it via `renderWithProviders`, then calls `rerender()` with the same component.
3. Asserts the captured `QueryClient` instance is referentially equal across both renders (`Object.is(first, second) === true`).

If the wrapper still reconstructs on re-render, the test fails on the second render. If the `useRef` fix is in place, the test passes. **Task 8 ships both the fix AND the test in the same commit** so the regression can't silently come back.

The Task 8 implementer prompt must reference this entry as a required deliverable, not as a "see also" footnote.

### 4.8 `tailwind-merge` doesn't know about custom M3 type-scale tokens — register them in the `font-size` group

**Why:** `tailwind-merge`'s default class-group registry knows about Tailwind's stock utilities. It treats every `text-*` utility as a single conflict group by default — meaning `text-label-lg` (a size) and `text-on-primary` (a color) are seen as conflicting, and `tailwind-merge` will silently drop one when both appear in the same `cn()` call. **This is the silent variant of the byte-exact rule** — your code looks right, the verify chain doesn't catch it (no `dark:`, no hex, no inline style), but the rendered button has the wrong text color or wrong size.

The bite is exactly: any primitive that combines a size class (`text-label-md`, `text-title-md`, etc.) with a variant text-color class (`text-on-primary`, `text-on-surface`, `text-error`, etc.) loses one of them. Button is the canonical case — `sizeClasses.md = "h-11 px-5 text-label-lg"` and `variantClasses.primary = "... text-on-primary"`. Without the fix, `cn(base, "text-label-lg", "text-on-primary", ...)` produces `"... text-on-primary"` (size dropped) or `"... text-label-lg"` (color dropped), depending on order.

**Caught at implementation time in Task 11** by the TDD test "renders with the primary variant gradient and merges consumer className via cn." The test asserts `button.className` contains BOTH `text-on-primary` AND the size class. The first run of the test failed because `tailwind-merge` had stripped one. Diagnosed by reading the assertion failure.

**How to apply:** Use `extendTailwindMerge` in `cn.ts` to register all M3 type-scale tokens in the `font-size` class group:

```ts
import { type ClassValue, clsx } from "clsx";
import { extendTailwindMerge } from "tailwind-merge";

const twMerge = extendTailwindMerge({
  extend: {
    classGroups: {
      "font-size": [
        "text-display-lg", "text-display-md", "text-display-sm",
        "text-headline-lg", "text-headline-md", "text-headline-sm",
        "text-title-lg", "text-title-md", "text-title-sm",
        "text-body-lg", "text-body-md", "text-body-sm",
        "text-label-lg", "text-label-md", "text-label-sm",
      ],
    },
  },
});

export function cn(...inputs: ClassValue[]): string {
  return twMerge(clsx(inputs));
}
```

Once registered in `font-size`, the type-scale tokens become a separate conflict group from text-color utilities, and `cn(base, "text-label-lg", "text-on-primary")` correctly produces `"... text-label-lg text-on-primary"` (both kept).

**Why the registry needs all 15 tokens, not just the ones used today:** every primitive in Tasks 11-25 will compose at least one size class with at least one text color. Registering all 15 up front avoids whack-a-mole as new primitives introduce new size combinations. The registration is one-time, ~15 lines, zero runtime cost.

**General rule for custom Tailwind tokens:** any token whose name starts with a Tailwind utility prefix (`text-`, `bg-`, `border-`, `ring-`, `shadow-`, etc.) AND that semantically belongs to a different group than `tailwind-merge`'s default classification will produce silent merge bugs. When introducing a new token category in `globals.css`, immediately register it in `cn.ts`'s `extendTailwindMerge` config in the appropriate group. Group names match `tailwind-merge`'s built-in groups: `font-size`, `text-color`, `font-weight`, `font-family`, `bg-color`, `border-color`, `ring-color`, `shadow`, `border-radius`, etc.

### 4.9 `container.firstChild` is never null inside `renderWithProviders` — `next-themes` injects a `<script>` element

**Why:** When `renderWithProviders` wraps a component in `<ThemeProvider>` from `next-themes`, the provider injects a `<script nonce>` element directly into the rendered tree to synchronize theme class application before React hydration. This means the RTL `container` element returned by `render()` always has at least one child (the script), even if your wrapped component returns `null`. Tests that assert "the component renders nothing" via `expect(container.firstChild).toBeNull()` will fail because `firstChild` is the next-themes script, not null.

**Caught at implementation time in Task 15** by the StatusBadge null short-circuit test. The spec's canonical assertion was `expect(container.firstChild).toBeNull()`, but the test failed because `next-themes` script was always present.

**How to apply:** Use an element-targeted query that matches what your component would render if it weren't returning null:

```ts
// Wrong — fails because next-themes script is always firstChild
const { container } = renderWithProviders(<MyComponent />);
expect(container.firstChild).toBeNull();

// Right — query for the specific element your component would render
const { container } = renderWithProviders(<MyComponent />);
expect(container.querySelector("span")).toBeNull();
// or for a component that renders a div:
expect(container.querySelector("div[data-testid='my-component']")).toBeNull();
```

The substitute query targets the component's actual output element. If the component returns null, the query returns null. If the component accidentally renders something (regression), the query returns the element and the assertion fails — preserving the spec intent exactly.

**General rule:** any test that wants to assert "the wrapped component rendered nothing" must target the component's specific output element, not `container.firstChild`. The next-themes script is always at `firstChild` position.

---

## §5 Project conventions

### 5.1 Conventional Commits with scope

**Format:** `<type>(<scope>): <subject>`

**Types:** `feat`, `fix`, `chore`, `docs`, `test`, `refactor`.

**Scope examples:** `frontend`, `ui`, `layout`, `lib`, `theme`, `routing`, `deps`, `test`.

**How to apply:** Every commit message subject follows this. The plan's task templates already supply the exact commit message — use it verbatim. Body is brief (1-2 lines of motivation) or empty. **Never paste the diff into the body** — git already shows the diff.

### 5.2 No `--no-verify`, no AI attribution

**Why:** User memory says: skip-hooks bypasses pre-commit checks that exist for a reason; AI attribution clutters the commit log and isn't truthful (the human did the work, the AI assisted).

**How to apply:**
- Never pass `--no-verify` (or `--no-gpg-sign`, `-c commit.gpgsign=false`, etc.)
- Never add `Co-Authored-By: Claude...` trailers
- Never add "Generated with Claude Code" footers
- Never mention Claude / Anthropic / AI in commit messages

### 5.3 One commit per task, atomic

**Why:** Each plan task is a reviewable unit. Stacking multiple commits per task fragments the review, and squashing later loses the granularity. Single commit per task keeps `git log --oneline` legible at scale.

**How to apply:** If you need to fix something during a task, `git commit --amend --no-edit` keeps the single commit. The amend changes the SHA but keeps the message and atomicity. Don't create a "fixup" commit.

### 5.4 Embedded "verbatim copies" in implementer prompts must be byte-exact to the canonical source

**Why:** When an implementer prompt says "copy this block from spec §X verbatim" AND ALSO embeds the block inline in the prompt for convenience, the embed becomes the de facto source. The implementer copies from the most immediate source (the prompt body), and any drift between the embed and the canonical spec ships into the file. The reviewer catches it later by comparing against the canonical spec, not the prompt.

**Caught at implementation time in Task 5.** The plan's inline copy of spec §4.1's token block had abbreviated section comments compared to the canonical spec. The first-pass implementer copied from the inline block faithfully. The spec reviewer caught 21 byte-exact violations against §4.1. The fix subagent restored the comments from §4.1 directly. Cost: one extra dispatch and ~10 minutes of cycle time.

**How to apply:**
- **Prefer**: tell the implementer to read the canonical source directly. "Open spec §X.Y and copy the block verbatim. Do not work from a copy in this prompt."
- **If embedding is unavoidable** (e.g., the implementer doesn't have file-read access to the spec): the embed must be a literal `cat`/extraction of the canonical block, generated mechanically, not retyped or summarized. Diff the embed against the canonical source before sending the prompt.
- **Always state the rule explicitly**: "If the embedded copy in this prompt and the canonical source disagree, the canonical source wins. Source the file from the canonical, not from the embed."
- **Watch for**: section comments, inline trailing comments, blank lines between sections, header text — these are the most common drift points because they "feel like prose" and get tightened during copy-paste.

This is a controller-side discipline rule, not an implementer-side one. The implementer can only follow the prompt; it's the controller's job to make sure the prompt's embedded artifacts are faithful to source.

### 5.5 The verify chain is expected to fail until Task 6

**Status:** `npm run verify` currently fails at `verify:no-dark-variants` because `src/app/page.tsx` (the unmodified Next.js scaffold) contains `dark:bg-black`, `dark:invert`, etc. **This is correct behavior** — the verify chain is doing its job.

**Action item:** Task 6 rewrites `src/app/page.tsx` as a minimal placeholder that uses semantic tokens, not `dark:` variants. After Task 6, `verify:no-dark-variants` should pass. After all 26 implementation tasks, the entire `npm run verify` chain should be green. The `verify-coverage.sh` is currently a stub (committed in Task 3); Task 27 replaces it with the real script.

### 5.6 Reviewer prompts inherit the byte-exact rule — paraphrasing the spec creates false positives

**Why:** When a controller writes a spec-compliance reviewer prompt, the natural urge is to summarize the spec into a compact table or checklist. That summary is itself an embed of the canonical, and any drift between the summary and the source becomes a fake "rule" the reviewer enforces. The implementer (who copied the canonical exactly) gets flagged for a violation that doesn't exist, and the controller wastes a dispatch chasing a phantom fix — or worse, lets the reviewer "correct" code that was already right.

**Caught at review time in Task 26.** The reviewer prompt summarized the eleven placeholder pages in a table and added an inferred rule: "the metadata `title` matches the page's displayed title (the only exception being `/auction/[id]`)." That rule wasn't in the canonical spec — the canonical actually has `metadata.title = "About"` but `<PageHeader title="About SLParcels" />`, and `metadata.title = "Terms"` but `<PageHeader title="Terms of Service" />`. The implementer copied the canonical correctly. The reviewer flagged two false-positive failures because it was matching against the controller's paraphrase instead of the canonical. No fix was applied; the controller had to override the FAIL.

**How to apply:**
- **Reviewer prompts get the same byte-exact discipline as implementer prompts.** If you tell the implementer "copy from canonical §X verbatim," tell the reviewer "compare against canonical §X verbatim." Don't paraphrase the spec into a table for the reviewer's convenience.
- **If a reviewer prompt embeds canonical content** (e.g., expected file contents), generate the embed mechanically — extract the literal block from the spec/plan, don't retype or summarize. The reviewer must compare bytes against bytes.
- **State the precedence rule explicitly in the reviewer prompt:** "If this prompt's summary disagrees with the canonical source, the canonical source wins. Re-read the canonical before flagging a violation."
- **High-risk paraphrasing patterns to avoid:** "X must match Y" rules inferred from a few examples; tables that compress field-by-field detail into a single column; "every page should have …" generalizations that aren't stated in the spec.
- **When you spot a reviewer false positive:** add a §5 entry rather than just overriding it — the next reviewer prompt you write will be tempted to paraphrase the same way unless the rule is documented.

This is a controller-side discipline rule. The reviewer can only follow the prompt; it's the controller's job to make sure the prompt's spec summary is faithful — or, better, that the prompt points the reviewer at the canonical directly.

### 5.7 Final ship step is `gh pr create` against a branch, never a direct push to main

**Why:** The end of a multi-task plan feels like the natural moment to "just push it" — all the gates are green, the controller has been verifying continuously, the user has been approving each phase. The temptation is to skip the PR ceremony and fast-forward main directly. **Don't.** A direct push to main:

- Bypasses the GitHub PR description as the canonical record of what shipped (smoke test artifact, automated gate evidence, brief/spec/plan links, process notes — none of those exist anywhere if there's no PR).
- Loses the merge commit that bookmarks "everything before this SHA was foundation work" — the history just becomes a flat list of 40 commits with no summary node.
- Forces a destructive force-push to undo if anyone (including the user themselves, on another machine) lands a commit on main between the direct push and the realization that a PR was wanted. The undo is `--force-with-lease=main:<expected-sha> origin <pre-push-sha>:main` plus a cherry-pick or rebase if the in-between commit needs to be preserved — recoverable, but ugly and gross-feeling on a shared branch.
- Means the user can't easily check out the work locally if the worktree still holds the branch — the branch only exists inside the worktree, not on the remote, so a `git checkout` from the parent repo fails with "already used by worktree."

**Caught at ship time on Task 01-06.** The controller pushed the worktree's 40 commits directly to `origin/main` as the final ship step. The user wanted a real PR — both for the GitHub artifact (smoke test, references, process note) and because the worktree still held the branch, blocking local checkout. The recovery path was: force-with-lease main back to the pre-foundation SHA, push the branch as `task/<name>`, open the PR via `gh pr create`, merge via the GitHub UI with the "Merge commit" option (not squash, not rebase — preserve the per-task atomic commits). Recoverable in ~5 minutes, but the lesson is to do it right the first time.

**How to apply:**
- **Default ship step is `gh pr create --base main --head <task-branch>`**, with a body that includes the smoke test artifact (with unchecked boxes for the user to fill in), the automated gate evidence, links to brief/spec/plan/FOOTGUNS, and a one-paragraph process note. The user reviews on GitHub, walks through the smoke test on their dev machine, ticks the boxes via the GitHub UI, and merges from there.
- **Merge with the "Merge commit" option, not squash, not rebase** — atomic per-task commits are the whole point of single-commit-per-task discipline (§5.3); squashing them away undoes that work.
- **If the user explicitly says "push directly to main"** (e.g., for a tiny chore commit that doesn't warrant PR ceremony), that's an override and is fine — but it must be explicit, not inferred from "we're done." Authorization stands for the scope specified, not beyond.
- **Add this rule to the B-mode prompt template's "ship checklist" section** so the controller doesn't have to remember it during the dopamine rush of finishing a long plan.

This is a controller-side workflow rule. There's no implementer or reviewer involvement — it's about how the controller closes out a finished plan.

### 5.8 Load-bearing documentation pattern — document WHY, not just WHAT

**Why:** Some decisions in specs are not stylistic — they're structural guards against future
refactoring that would quietly degrade the security or correctness of the system. Examples from
the Task 01-07 JWT auth spec:

- The `AuthResult` (service-internal) vs `AuthResponse` (controller-external) **two-record split**
  exists so the type system prevents refresh-token leakage into JSON bodies. If you merge them
  "for simplicity," you've removed the structural guard.
- The **`Path=/api/auth` non-widening rule** on the refresh cookie is what makes cookie-only
  logout CSRF-safe. Widening the path to `/` is a ten-character change that breaks the security
  model.
- The **`@RestControllerAdvice(basePackages = "...auth")`** scoping on `AuthExceptionHandler`
  prevents cross-slice exception leakage. Removing the scope "to simplify" means the auth
  handler starts catching user-slice exceptions and producing wrong error codes.
- The **refresh-token reuse-cascade integration test** is a canary. If a future contributor
  "optimizes" it away because "we already have a unit test for the service," they've disabled
  the entire defense that makes DB-backed refresh tokens worth the cost over JWT refresh tokens.

In each case, the code alone doesn't tell you why the thing can't be refactored — the decision
is only load-bearing if the next contributor *understands* it's load-bearing.

**How to apply:**

- When a spec locks a decision that would pass code review if a future contributor refactored it
  away, **write a JavaDoc or comment block explaining why the decision can't be refactored.**
  Not "this record is for serialization" but "this record exists as a structural guard against
  a specific failure mode. Do NOT merge it with X for simplicity — the split is the guard."
- **Reference the FOOTGUNS ledger entry by number** so future contributors have a one-hop link
  to the full rationale. The JavaDoc is the warning; the ledger entry is the reasoning.
- **Watch for simplification PRs in code review.** A PR that says "simplified the auth DTOs by
  merging AuthResult and AuthResponse" is a red flag regardless of how clean the diff looks.
  Load-bearing decisions look like over-engineering to contributors who don't know why they exist.
- **Apply this to every future security-critical spec.** The pattern is: identify the decisions
  that future contributors will be tempted to refactor away, document the *why*, reference the
  ledger, and trust that the documentation is load-bearing in the same way the code is.

This is a meta-lesson for spec writing, not an implementation footgun. It applies across
frontend and backend slices equally. Filed here under §5 (project conventions) because it's a
convention about how to write specs, not a domain-specific gotcha.

### 5.9 Stale briefs are a drift source — patch N+1 briefs when Task N lands contradicting decisions

**Why:** When Task N's spec or plan locks a decision that contradicts the original brief for Task N+1, patch the N+1 brief in the same PR. Otherwise N+1's implementer walks into a pre-resolved conflict and wastes a brainstorm round re-discovering the answer.

**Caught at brainstorm time in Task 01-08.** Task 01-07 locked in-memory access tokens + HttpOnly refresh cookies three days before Task 01-08's brainstorm, but Task 01-08's brief still said "localStorage or httpOnly cookie" and "remember me checkbox controls localStorage vs sessionStorage." The brainstorm spent the entire Q1 round re-litigating a resolved decision before reaching the same conclusion Task 01-07 had already documented.

**How to apply:**
- When shipping a spec that contradicts an upstream brief for a dependent task, grep for the dependent task's brief in the same PR and patch any statements that no longer apply.
- Leave a one-line `> **Brief updated post-Task N-1:** <description>` note so the N+1 reader knows a correction happened.
- This is a controller-side discipline rule, not a per-task footgun. It applies to every cross-task dependency.

This is filed under §5 (project conventions) because it's a process rule that applies to every future task handoff, not a domain-specific gotcha.

---

## §6 Scaffold / template / generated content

### 6.1 Toptal `.gitignore` template's unanchored `lib/` rule silently ignores `frontend/src/lib/`

**Why:** The repo's root `.gitignore` was generated from the toptal Python+React template at scaffold time. The template includes `lib/` and `lib64/` (without leading slashes) intended to ignore Python compiled-library output directories at the repo root. **Without a leading `/`, gitignore treats the rule as matching `lib/` ANYWHERE in the repo tree.** That includes `frontend/src/lib/` — which is exactly where we put `cn.ts`, `api.ts`, `auth.ts`, and any future utility module.

The bite is silent: `git add frontend/src/lib/cn.ts` returns success but stages nothing. `git status` shows clean working tree. The file exists on disk and the build/test commands all pass because Node and Vitest read from disk, not from git. The breakage only surfaces when someone clones the repo and the file isn't there. Or in our case, when the implementer notices that `git add` didn't pick up the file.

**Caught at implementation time in Task 7** by the implementer running `git status` after creating the lib files and noticing the new files weren't listed. Diagnosed by `git check-ignore -v frontend/src/lib/cn.ts` which reported `.gitignore:53:lib/`.

**How to apply:** Anchor the rule to the repo root with a leading `/`:

```
# Before (matches every lib/ directory in the repo, including src/lib/)
lib/
lib64/

# After (matches only repo-root lib/ and lib64/)
/lib/
/lib64/
```

The fix landed in commit `fba3022` and unblocked Tasks 7, 8, and 9. **Always audit the scaffold's `.gitignore` early** — `git check-ignore -v <some/path>` is the diagnostic command for "why isn't git tracking this file."

**General pattern for scaffold gotchas:** Generated `.gitignore` / `package.json` / `next.config.ts` / `eslint.config.mjs` files from `create-next-app`, `npm init`, toptal templates, etc. all carry assumptions that may not match your project structure. The first task in any new repo's plan should include a "scaffold audit" pass that checks for: unanchored gitignore rules, missing dependencies in package.json, defaults in next.config.ts that conflict with your structure, eslint rules that don't match your conventions. This isn't this plan's first task, and we're catching them as we hit them — but for future plans, an explicit Task 0 audit would be cheap insurance.

---

## How to update this ledger

When a reviewer (spec or code quality) finds a real issue that wasn't anticipated by the implementer prompt:

1. Fix the issue in the task (via amend or follow-up commit, depending on severity).
2. **Before dispatching the next task's implementer**, add a new entry to the relevant section of this ledger.
3. The entry should be: rule (what to do), why (the underlying mechanism that bites), how to apply (concrete code/command).
4. Reference the new entry from the next task's implementer prompt: "See FOOTGUNS §X.Y for the [topic] rule."

---

## §B. Backend / Spring Security / JJWT

Backend-domain footguns. Numbered `§B.1`, `§B.2`, etc. to keep the namespace separate from the
frontend-domain sections (`§1`–`§6`) so search stays clean.

### B.1 `@AuthenticationPrincipal AuthPrincipal principal`, never `UserDetails`

**Why:** Spring Security's tutorial-default principal type is `UserDetails`. Reaching for it in
the SLParcels codebase yields `null` at runtime because `JwtAuthenticationFilter` sets an
`AuthPrincipal` record into the `SecurityContext`, not a Spring `UserDetails`.

**How to apply:**
- Every controller uses `@AuthenticationPrincipal AuthPrincipal principal`.
- Never reach for `UserDetails` in a controller method signature — it silently yields `null`.
- A backend grep verify rule (mirror of the frontend's `npm run verify` chain) should flag
  `@AuthenticationPrincipal UserDetails` as a build break. Implementation of the rule is a
  follow-on task; for now, code review enforces it.

### B.2 `AuthenticationEntryPoint` bypasses the message converter chain

**Why:** `AuthenticationEntryPoint.commence()` runs outside Spring's message converter chain.
Returning a `ProblemDetail` from the method signature does nothing — the entry point is not an
`@ExceptionHandler`, so Spring's auto-conversion doesn't fire.

**How to apply:**
- `JwtAuthenticationEntryPoint` serializes the `ProblemDetail` manually via injected `ObjectMapper`.
- Set `Content-Type: application/problem+json`, status code, character encoding, and
  `Cache-Control: no-store` explicitly.
- If a future entry point is added (e.g., an OAuth error path), follow the same manual-serialization
  pattern. Copying a `ProblemDetail` return from a regular exception handler won't work.

### B.3 `WithSecurityContextFactory` wiring in Spring Security 6

**Why:** Spring Security 6 auto-discovers `WithSecurityContextFactory` implementations from
the `@WithSecurityContext(factory = ...)` element on the custom annotation itself. Legacy Spring
Security (5 and earlier) required explicit registration in `META-INF/spring.factories`.

**How to apply:**
- Use `@WithSecurityContext(factory = YourFactory.class)` directly on the annotation.
- If the annotation is silently ignored in tests (principal is `null` inside the controller under
  test), fall back to `META-INF/spring.factories`. Verify the primary path first — the fallback
  is a last resort.
- Document which path was used in `auth/test/README.md` for the next test author.

### B.4 `jwt.secret` must be present in every active profile

**Why:** `JwtConfig.@PostConstruct` validates on startup and throws if `jwt.secret` is missing or
shorter than 256 bits. Adding a new application profile (e.g., `application-test.yml`) for other
reasons later will cause every test in that profile to fail before the first assertion runs
unless the profile inherits or re-declares `jwt.secret`.

**How to apply:**
- Any new profile that loads Spring config must have `jwt.secret` set — either inherit from base
  `application.yml` (which reads `${JWT_SECRET}`) or declare explicitly.
- Tests that use `@TestPropertySource` must include `jwt.secret` in their properties block.
- The fail-fast validation is intentional — do not weaken it by allowing null in dev profiles.

### B.5 `SecurityConfig` matcher ordering

**Why:** Spring Security matches `requestMatchers` rules in declaration order. The current SLParcels
ordering is safe because every rule is exact-match (no prefix wildcards). Adding a prefix matcher
like `/api/auth/**` without understanding the consequences will break the explicit
`/api/auth/logout-all authenticated()` rule — the prefix rule would swallow it unless declared
after.

**How to apply:**
- Do not add prefix matchers (`/**` suffixes) without verifying the impact on existing exact-match
  rules.
- Do not reorder `authorizeHttpRequests` rules without understanding why the current order exists.
- The inline comment in `SecurityConfig` documents this constraint at the call site. Read it
  before modifying.

### B.6 Refresh token reuse cascade is the security model

**Why:** The `refreshTokenReuseCascade_revokesAllSessionsAndBumpsTokenVersion` integration test
in `AuthFlowIntegrationTest` is a canary. If a future contributor "optimizes" the refresh path by
skipping the revoked-row check in `RefreshTokenService.rotate`, they've degraded the auth slice
from "stateful with revocation" to "stateless without it" without realizing. The test is the
only thing that catches this regression — the unit tests mock the repository and wouldn't notice.

**How to apply:**
- **Never delete the reuse-cascade integration test.** Removing it is equivalent to removing the
  security feature.
- If the test is flaky or slow, fix the flake — do not quarantine or delete.
- Any PR that touches `RefreshTokenService.rotate` must run the integration test and assert it
  still passes.

### B.7 Logout endpoint idempotency

**Why:** `POST /api/auth/logout` must always return 204, even if the cookie is missing,
malformed, expired, revoked, or points to another user's token. Throwing a 401 on an
already-revoked token would create a "is this token still alive?" oracle through the logout
endpoint — an attacker with a bunch of candidate raw tokens could test them by checking the
logout response code.

**How to apply:**
- `AuthService.logout` and `RefreshTokenService.revokeByRawToken` are idempotent and never throw.
- The controller's `/logout` handler catches nothing — it just returns 204 after the service call.
- Do not add logging that distinguishes "successful revoke" from "cookie was already revoked" —
  both cases should produce the same log output (or none).

### B.8 Refresh token raw value never lives in the DB

**Why:** The refresh token's raw 256-bit random value exists only in the HttpOnly cookie on the
wire and in the client's cookie jar. The database stores only the SHA-256 hash. If the DB leaks,
tokens leak as hashes — not usable credentials. This is the entire reason refresh tokens are
worth persisting at all.

**How to apply:**
- If a future migration or debug feature adds a `raw_token` column to `refresh_tokens`, roll it
  back immediately. "Temporary debug column" is not an acceptable reason.
- `RefreshTokenService` hashes on every write; the raw value only crosses the API boundary as a
  return value from `issueForUser` and `rotate`.
- The `RefreshTokenTestFixture` also hashes — tests that insert rows use the raw value only for
  replay through the API, not for DB assertions.

### B.9 JJWT 0.12+ API is different from 0.11

**Why:** JJWT 0.12 introduced a new builder API. Stack Overflow examples showing
`Jwts.builder().setSubject(...)` and `signWith(SignatureAlgorithm.HS256, secret)` are JJWT 0.11
syntax and will not compile against 0.12. The 0.12 form is `Jwts.builder().subject(...)` and
`signWith(secretKey)` with the algorithm inferred from the key.

**How to apply:**
- Use `Jwts.parser().verifyWith(key).build().parseSignedClaims(token)` — not
  `Jwts.parserBuilder()` (deprecated).
- When copying JJWT examples from the internet, check the library version in the example first.
- The `JwtService` and `JwtTestFactory` classes demonstrate the correct 0.12 API — reference them
  for new JWT code.

### B.10 `AuthResult` (service-internal) vs `AuthResponse` (controller-external) two-record split

**Why:** The two-record split exists so the type system catches any code path that would put a
refresh token in a JSON body. `AuthResult` (internal, service-to-controller) carries
`{accessToken, refreshToken, user}`. `AuthResponse` (external, controller-to-client) carries
`{accessToken, user}` — no refresh token field. A handler that returns `AuthResponse` cannot
leak the refresh token because the field doesn't exist on the type.

**How to apply:**
- **Do not merge `AuthResult` and `AuthResponse` "for simplicity."** The split is the structural
  guard; merging removes the guard.
- Every new auth endpoint that returns tokens must return `AuthResponse`, not `AuthResult`.
- The JavaDoc on both records documents this — don't delete the JavaDoc either.
- If a future contributor proposes a PR that merges the records, the PR review should reference
  this entry and decline.

This is non-negotiable. Without the meta-discipline of "every catch becomes a ledger entry," the ledger doesn't compound and we re-burn the same lessons.

---

## §F. Frontend / React / Next.js / Auth

Frontend-domain footguns. Numbered §F.1, §F.2, etc. to keep the namespace separate from the existing frontend domain-shaped sections (§1–§6) and the backend section (§B). When the frontend ledger grows larger, sub-sections may mirror the backend pattern (§F.1, §F.2, ...) or use a domain-flat list — both work.

### F.1 Access token lives in a module-level `let`, not a React `useRef`

**Why:** The API client interceptor in `lib/api.ts` runs outside React's lifecycle. React refs (`useRef`) are per-component and die on unmount; they can't be read from a non-React context. A module-level `let` in `lib/auth/session.ts` is the correct container for state that the interceptor reads synchronously and mutations write synchronously.

**How to apply:**
- Only `getAccessToken` and `setAccessToken` are exported. The `accessToken` variable itself is module-private.
- A grep for `accessToken = ` outside `session.ts` catches any mutation path that bypasses the setter — code review enforces the rule until a lint check ships.

### F.2 The session query's side effect (setting the access token) lives inside `queryFn`, not `onSuccess`

**Why:** TanStack Query's first subscriber receives `queryFn`'s return value directly — `onSuccess` only fires on subsequent subscribers and refetches. If `setAccessToken(response.accessToken)` lives in `onSuccess`, the first component to call `useAuth()` gets the user but the access token ref stays null until the next refetch, which may never happen with `staleTime: Infinity`.

**How to apply:** Place the side effect inside `queryFn` BEFORE returning the user value. The first-subscribe path will then update the token synchronously with the query cache. `bootstrapSession()` in `lib/auth/hooks.ts` is the canonical example.

### F.3 `configureApiClient(queryClient)` — pass the QueryClient via setup function, not module global

**Why:** The 401 interceptor in `lib/api.ts` needs to update the session query cache on failed refresh. Storing the QueryClient as a module global creates import-order footguns — the API client module might initialize before the QueryClient exists. A setup function called once at app mount makes the dependency explicit and testable.

**How to apply:** `app/providers.tsx` calls `configureApiClient(client)` inside the `useState` initializer when the QueryClient is constructed. The API client stores it at module scope. Tests construct their own QueryClient and call `configureApiClient` in `beforeEach`.

### F.4 Concurrent 401 stampede — share one in-flight refresh promise

**Why:** If three requests return 401 simultaneously, the naive interceptor fires three `/refresh` calls. Deduplicate via a single `inFlightRefresh: Promise<void> | null` at module scope. The first 401 starts the refresh and stores the promise; subsequent 401s await the same promise; all retry after it resolves.

**How to apply:** The cleanup (`inFlightRefresh = null`) lives INSIDE the IIFE's `try/finally` so only the creator clears the ref, not every awaiter racing to null it out. JS microtask ordering makes the per-awaiter pattern safe in practice, but having one code path own the cleanup is structurally correct and easier to reason about. See `handleUnauthorized` in `lib/api.ts`.

### F.5 `staleTime: Infinity` + `gcTime: Infinity` + `retry: false` on the session query — all three are load-bearing

**Why:**
- `staleTime: Infinity` prevents auto-refetching the session on every `useAuth()` call. Without it, TanStack Query considers the query stale after 5 minutes and re-fetches.
- `gcTime: Infinity` prevents garbage collection if `Header` unmounts temporarily.
- `retry: false` prevents three `/refresh` calls on a fresh visit — a 401 on bootstrap is a legitimate unauthenticated state, not a transient error.

**How to apply:** A contributor "tuning" these based on standard React Query advice would break the auth layer. A comment block above the `useQuery` call in `lib/auth/hooks.ts` explains each flag.

### F.6 `mapProblemDetailToForm` — `errors` is a `Record<string, string>` dict, not an array

**Why:** The backend's `GlobalExceptionHandler` (Task 01-07 Task 23) emits the validation errors map as `errors: Record<string, string>` — a dict like `{ email: "must be a valid email", password: "must be at least 10 characters" }`. The original Task 01-07 design intent showed an array of `{field, message}` objects, but the actual implementation uses the dict form because the existing user-slice tests asserted against `$.errors.email` as a map key.

**How to apply:**
- Use `Object.entries(problem.errors)` to iterate, not `Array.prototype.forEach`.
- Validate the shape with `typeof problem.errors === "object" && problem.errors !== null && !Array.isArray(problem.errors)` if defensive checks are needed.
- The dict form means each field can have only one error message at a time. If multi-error-per-field is ever needed, the backend would need a redesign — flag it then.

This is a load-bearing correction that contradicts an earlier note. The dict form is the actual production shape.

### F.7 `mapProblemDetailToForm` — unknown-field guard with console warn in dev

**Why:** If the backend returns a `VALIDATION_FAILED` with a field that doesn't exist on the form, silent dropping hides drift between backend validators and frontend form fields.

**How to apply:** Fall back to `root.serverError` with the concatenated message and `console.warn` in dev (`process.env.NODE_ENV !== "production"`). The warn is a drift-detection mechanism — the form tells you "something new is being validated that you don't know about" rather than failing silently. See `mapProblemDetailToForm` in `lib/auth/errors.ts`.

### F.8 `onUnhandledRequest: "error"` in MSW setup is load-bearing

**Why:** A future contributor who switches this to `"warn"` or `"bypass"` to make a flaky test pass has silently allowed real network requests from tests. That's the same failure mode as deleting a canary integration test — it masks a real integration gap.

**How to apply:** Do not relax it. If a test is flaky because a handler is missing, add the handler; don't widen the escape hatch. `vitest.setup.ts` calls `server.listen({ onUnhandledRequest: "error" })` and this must not change.

### F.9 The 401-auto-refresh canary is the frontend security model

**Why:** The integration test in `lib/api.401-interceptor.test.tsx` verifies "401 on protected endpoint → auto-refresh → retry → success." It also covers stampede protection and refresh-failure-redirect. These three tests together prove the API client's self-healing behavior works.

**How to apply:** **Never delete. Never quarantine.** If the canary fails, debug the interceptor — don't disable the test. The frontend equivalent of Task 01-07's `refreshTokenReuseCascade` integration test.

### F.10 `useLogoutAll` must refresh before calling the endpoint

**Why:** `POST /api/auth/logout-all` requires a valid access token (it's protected). If the user's access token is already expired when they click "Sign out all sessions," the call fails with 401 and the interceptor triggers a refresh anyway — but the user sees a flicker. Cleaner: the `useLogoutAll` hook always calls `/refresh` first, gets a fresh access token, then calls `/logout-all`.

**How to apply:** Refresh is cheap (~100ms). The hook in `lib/auth/hooks.ts` does both calls in the `mutationFn`.

### F.11 `onSettled` for logout, not `onSuccess`

**Why:** Logout should clear local state even if the network call fails. A user clicking "Sign Out" expects to be logged out regardless of whether the backend acknowledged the POST.

**How to apply:** Place `setAccessToken(null)`, `setQueryData(null)`, `removeQueries`, and `router.push("/")` in `onSettled` (not `onSuccess`). All four operations are idempotent.

### F.12 `onSuccess` on login/register uses `setQueryData`, not `invalidateQueries`

**Why:** `invalidateQueries` would trigger a wasted `/refresh` round-trip immediately after login. `setQueryData(["auth", "session"], response.user)` directly seats the cached value, so `Header` re-renders instantly without a network call.

**How to apply:** Use `invalidateQueries` only when you actually want to refetch from the server. For login/register, the response body already contains the user — there's nothing to refetch.

### F.13 DESIGN.md §2 "No-Line Rule" wins over Stitch HTML

**Why:** The Stitch design HTML often contains `border-t` or `border-b` classes that violate DESIGN.md §2. When a section separator is needed, use a background-color shift (e.g., `bg-surface-container-low`) or vertical spacing (`pt-6`) instead of a border.

**How to apply:** The Stitch HTML is a mockup; DESIGN.md is the rulebook. Example: `AuthCard.Footer` must not use `border-t border-outline-variant/10` even though the Stitch HTML shows it. Use the `bg-surface-container-low` strip with negative margins matching the underlying Card's padding (verify against `Card.tsx` before committing — wrong values cause horizontal overflow).

### F.14 `ApiError` constructor takes a single `ProblemDetail` argument, not three

**Why:** `lib/api.ts` exports `ApiError` with the constructor signature `new ApiError(problem: ProblemDetail)`, NOT the three-argument form `new ApiError(message, status, problem)` that some examples and tutorials show. The class derives `message` and `status` from the `problem` object internally.

**How to apply:**
- Always pass a `ProblemDetail` object: `throw new ApiError({ status: 401, code: "AUTH_TOKEN_MISSING", title: "..." })`
- When mocking errors in tests, use the same single-arg form
- If you find code calling `new ApiError("msg", 401, problem)`, that's a bug — adapt to the single-arg form

Discovered when implementing `mapProblemDetailToForm` tests in Task 01-08. The plan code used the three-arg form and the tests failed at compile time.

### F.15 `vitest.config.ts` uses `globals: false` — `beforeAll`, `afterEach`, etc. need explicit imports

**Why:** Task 01-06 set `globals: false` in `vitest.config.ts` to enforce explicit imports per the project's no-magic-globals discipline. This means `beforeAll`, `afterEach`, `afterAll`, `beforeEach`, `describe`, `it`, `expect`, `vi` all need to be imported explicitly from `"vitest"` at the top of every test file AND in `vitest.setup.ts`.

**How to apply:**
- Existing test files already import these correctly via `import { describe, it, expect, vi } from "vitest"`.
- When adding new lifecycle hooks (`beforeAll`/`afterAll` for MSW setup, etc.) to `vitest.setup.ts`, expand the existing `import { afterEach, vi } from "vitest"` line to include them.
- A missing import surfaces as `ReferenceError: beforeAll is not defined` at test runtime. The fix is one line.

Discovered during MSW lifecycle wiring in Task 01-08 Task 4.

### F.16 WebSocket handshake is permitted at HTTP, authenticated at STOMP

**Rule:** `SecurityConfig` must list `/ws/**` as `.permitAll()` above the `/api/**` authenticated catch-all. The HTTP-layer WebSocket upgrade does not carry an `Authorization` header from the browser (the WebSocket API has no mechanism to set custom headers on upgrades), so gating the upgrade at the HTTP layer is impossible. Authentication happens in `JwtChannelInterceptor.preSend()` on the first STOMP `CONNECT` frame, before the session is usable for any subscription or send.

**Why:** Task 01-09 locked this model in the brainstorm (spec §3 Q1-A). Matcher order in `SecurityConfig` is first-match-wins (§B.5), so `/ws/**` must appear ABOVE `/api/**`. Moving it below the catch-all silently flips it from permitAll to authenticated and every WebSocket connection starts failing with a bewildering 401 before the STOMP layer even sees the frame.

**How to apply:** when editing `SecurityConfig.authorizeHttpRequests`, verify the `/ws/**` matcher is still above `/api/**`. If code review proposes "tightening" it to `.authenticated()`, reject — browsers cannot send the required header.

### F.16.1 Anonymous STOMP CONNECT is intentional — SUBSCRIBE gate is where auth lives

**Rule:** Epic 04 sub-spec 1 §4 requires `/topic/auction/**` to be publicly subscribable. `JwtChannelInterceptor.handleConnect` therefore accepts CONNECT frames with no `Authorization` header (no principal attached → session is anonymous). Authorization for per-destination access moves into `handleSubscribe`, which only lets anonymous sessions SUBSCRIBE to destinations matching the strict regex `PUBLIC_AUCTION_DESTINATION` — `^/topic/auction/\d+$`, nothing else. SEND and all other authenticated-destination SUBSCRIBEs require a principal.

**Why:** browsers cannot put a bearer on the WebSocket upgrade, and forcing auth on CONNECT would lock unauthenticated viewers out of the public auction feed — but auth still has to live somewhere. Moving it to SUBSCRIBE keeps the public topic genuinely public while preventing an anonymous session from piggybacking on the `/topic/ws-test` / `/user/**` queues. An invalid or expired token in CONNECT is still rejected — only *absence* of the header opts the session into anonymous mode.

**Why a regex instead of `startsWith`:** Spring's default simple in-memory broker treats every destination string as opaque — `/topic/auction/../ws-test` is just another literal with zero subscribers, so a crafted traversal is harmless *today*. But any future swap to a STOMP relay (RabbitMQ, ActiveMQ, etc.) that normalizes destination paths would let a prefix check like `startsWith("/topic/auction/")` escape the allowlist: the anonymous SUBSCRIBE passes the prefix gate, the broker normalizes to `/topic/ws-test`, and the session starts receiving auth-gated frames. The `^/topic/auction/\d+$` regex forces the suffix to be digits (auction primary key) only, so traversal segments, extra path components, and non-numeric ids all fail the match. `JwtChannelInterceptorTest#subscribe_toAuctionWithPathTraversal_isRejected` pins this.

**How to apply:** every new STOMP destination must be consciously labelled public or authenticated. Public → add a dedicated `Pattern` for its exact shape alongside `PUBLIC_AUCTION_DESTINATION` in `JwtChannelInterceptor` and OR it into `handleSubscribe`. Do NOT loosen to a prefix check. Authenticated → do nothing; the default path rejects anonymous SUBSCRIBEs. Never "simplify" the SUBSCRIBE branch back to a pass-through or a prefix check; doing so re-opens every current and future topic to anonymous subscribers on any path-normalizing broker.

### F.17 `ensureFreshAccessToken` stampede guard — `finally` inside the IIFE

**Rule:** In `lib/auth/refresh.ts`, the `inFlightRefresh = null` cleanup MUST live inside the IIFE's `try { ... } finally { inFlightRefresh = null; }`, not after the outer promise chain. The shared promise between HTTP 401 interceptor and STOMP `beforeConnect` depends on this: if the cleanup runs outside the IIFE, every awaiter nulls the ref as they resolve, and a concurrent refresh kicked off during the resolution window sees `null` and fires a second `/api/auth/refresh`, defeating the stampede guard.

**Why:** this is §F.4 restated for the extracted module. Two canary tests pin this behavior:
- `frontend/src/lib/auth/refresh.test.ts` (three tests, local to the extracted module)
- `frontend/src/lib/api.401-interceptor.test.tsx` (three tests, HTTP end-to-end via MSW)

If either canary starts failing with "fetch called twice instead of once", the `finally` clause has been moved.

**How to apply:** when touching `lib/auth/refresh.ts`, diff the `finally` placement. If code review ever proposes "flattening the IIFE for readability", reject it — the flattening breaks the contract.

### F.18 `beforeConnect` must not throw — stash errors and let stompjs produce the ERROR frame

**Rule:** In `lib/ws/client.ts`, the `beforeConnect` callback wraps `ensureFreshAccessToken` in try/catch. On `RefreshFailedError`, store the message via `setState({status:"error", detail})` and return normally — do NOT throw from `beforeConnect`. Stompjs treats a thrown `beforeConnect` as a catastrophic failure and either deactivates the client entirely or loops infinitely depending on the version.

**Why:** letting stompjs proceed with no `Authorization` header causes the interceptor to reject the CONNECT frame with an `ERROR` frame, which the client handles gracefully via `onStompError` and our `ConnectionState` machine. Any path that throws from `beforeConnect` hides the error from UI and potentially deadlocks the client.

**How to apply:** when reviewing `beforeConnect` changes, verify that the catch block only stores state, never throws or calls `client.deactivate()`. The setState call path is safe; throw is not.

### F.19 `@stomp/stompjs` `subscribe()` only works when `client.connected === true` — use the re-attach registry, not deferred listeners

**Rule:** `client.subscribe(destination, callback)` throws if called before the client is connected. Our `lib/ws/client.ts` handles this via a module-level registry: every `subscribe()` call immediately inserts a `SubscriptionEntry` into the `entries` Map (regardless of connection state), and then calls the idempotent `ensureAttached(entry)`. If `client.connected` is false, `ensureAttached` is a no-op and the entry sits with `handle === null`. On `onConnect`, a single sweep iterates every entry and re-attaches every null-handle one. On `onWebSocketClose`, every handle is nulled so the next `onConnect` sweep re-attaches everything from scratch.

**Why:** the earlier shape — a per-subscribe inline `subscribeToConnectionState` listener that unsubscribes itself on first fire — had a race: a rapid `onConnect → onWebSocketClose` sequence before the listener fired could leave the deferred subscribe waiting mid-cycle, delayed by a reconnect. The registry model removes the race entirely because the "attach" decision is purely structural: if the entry is in the Map and the client is connected, attach. Idempotency (checking `entry.handle !== null`) makes duplicate sweeps safe. Epic 04 sub-spec 2 Task 1 (§6) implemented this hardening.

**How to apply:** any new path that needs to subscribe to a STOMP destination goes through `subscribe()` in `lib/ws/client.ts`. Do not add ad-hoc `client.subscribe` calls elsewhere, and do not reintroduce the deferred-listener pattern — the registry sweep is the only attach path. See F.68 for the critical live-Map iteration invariant in the sweep.

### F.20 `LivePill` has no "use client" directive — do not add hooks

**Rule:** `components/marketing/LivePill.tsx` is a Server Component by design. It has no `"use client"` directive so it can be composed from both Server Component parents (HowItWorksSection, FeaturesSection) and Client Component parents (Hero, CtaSection) without forcing the entire parent tree to cross the client boundary. Adding `useEffect`, `useState`, or any React hook WITHOUT adding `"use client"` will build-error when imported from a Server parent. Adding `"use client"` unnecessarily ships the pulse animation's JS to every page consumer.

**Why:** Task 01-10 locked this shape in the brainstorm. The animated ping dot is achieved via Tailwind's `animate-ping` class — pure CSS, zero JavaScript. The component takes one prop (`children: ReactNode`) and that's enough. If future work needs per-instance state (e.g., fading the pill in/out on hover with JS), extract a separate `LivePillInteractive.tsx` client component instead of mutating the base.

**How to apply:** when touching `LivePill.tsx`, read the header comment first. If you're tempted to add a hook, stop and ask: does this really need JavaScript, or can Tailwind's animation utilities handle it? If JavaScript is required, create a new component with `"use client"` rather than breaking the existing one's dual-composability contract.

### F.21 `next-themes` + SSR: use the `mounted` hydration guard pattern

**Rule:** When a component reads `useTheme().resolvedTheme` to swap assets or classes based on the active theme, it MUST first check a `mounted` state that flips to `true` in a `useEffect`. Without the guard, the server renders one variant (theme unknown → `undefined`) and the client renders another (theme known → `"dark"`), causing a hydration mismatch that React logs loudly in dev and may render the wrong variant for a flash in production.

**The pattern:**

```tsx
const { resolvedTheme } = useTheme();
const [mounted, setMounted] = useState(false);
useEffect(() => {
  setMounted(true); // eslint-disable-line react-hooks/set-state-in-effect
}, []);
const variant = mounted && resolvedTheme === "dark" ? "dark" : "light";
```

**Why:** `next-themes` cannot know the real theme on the server because it depends on `localStorage` (user's stored preference) and `prefers-color-scheme` (browser media query), neither of which exists during SSR. The library injects the correct theme class onto the `<html>` element via a blocking script BEFORE React hydrates, but the JS `useTheme()` return value still reads `undefined` during the first React render pass. The `mounted` guard converts that first-render mismatch into a deterministic "always light, then swap after mount" sequence, which React can reconcile without warnings.

The inline `eslint-disable-line react-hooks/set-state-in-effect` is required because React 19's ESLint config flags setState calls in effects. The mounted guard is the documented escape hatch — `ThemeToggle.tsx`, `HeroFeaturedParcel.tsx`, and `FeatureCard.tsx` all use this exact pattern.

**How to apply:** when writing a component that needs theme-dependent rendering, copy the 4-line `mounted` guard verbatim including the eslint-disable comment. Do not use `theme` instead of `resolvedTheme` — `theme` can be `"system"`, which isn't a renderable value. For tests, use `renderWithProviders(<X />, { theme: "dark", forceTheme: true })` from `@/test/render` rather than mocking `next-themes` — the test helper's `forcedTheme` prop drives `resolvedTheme` deterministically.

### F.22 Refresh-token cookie `Path` must be renamed in lock-step with the endpoint path

**Why:** `AuthController.REFRESH_COOKIE_PATH` and the frontend MSW handler's `Set-Cookie: Path=/api/v1/auth` attribute must match the URL the refresh endpoint actually lives at (`/api/v1/auth/refresh`). If the endpoint moves but the cookie `Path` does not — or vice versa — the browser silently stops sending the refresh cookie on rotation. Every token refresh fails with 401 "missing cookie" and the failure mode looks like a cascade revocation rather than a path mismatch, so the bug burns hours before someone diffs the actual `Set-Cookie` header against the request URL.

Caught during the Task 1 `/api/*` → `/api/v1/*` migration: the controller constant got renamed but the MSW mock handlers still emitted `Path=/api/auth`, and the frontend 401-interceptor canary silently broke until the test run surfaced a `missing cookie` ProblemDetail that nobody had seen before.

**How to apply:** any future URL versioning, auth-path restructure, or cookie-scope tweak must touch all three sources in the same commit:

1. `AuthController.REFRESH_COOKIE_PATH` (the constant the real `Set-Cookie` builder uses).
2. Every MSW handler in `frontend/src/test/msw/handlers/auth.ts` (or wherever auth mocks live) that emits a `Set-Cookie` for `refreshToken`.
3. The `SecurityConfig` matcher list — the new path must be in the public-permit list, the old path must NOT be.

The `lib/api.401-interceptor.test.tsx` canary will flag a mismatch on the next test run if the cookie `Path` and request URL disagree, but only because the MSW mocks happen to enforce the contract. Don't trust the canary alone — treat the three sources above as a synchronized triple, not an "I'll fix the others later" punch list.

### F.23 `@Profile("dev")` alone is not a URL gate — `SecurityConfig` must permit the path

**Why:** Spring Security's filter chain runs before the MVC dispatcher resolves the handler bean. `@Profile("dev")` controls whether the controller bean is registered, but it does NOT influence the security filter's URL matching. If `DevSlSimulateController` is `@Profile("dev")` but `SecurityConfig` has no `permitAll()` for `/api/v1/dev/**`, requests in the `dev` profile fall through to the `/api/v1/**` catch-all matcher (`.authenticated()`) and get rejected as 401 by the JWT entry point before ever reaching the handler. The dev shortcut returns 401 "missing token" and the developer concludes the controller isn't wired — when in reality the security chain ate the request.

The opposite direction is harmless: leaving the permit matcher in for prod doesn't expose anything because the bean doesn't exist, so the request falls through to a clean 404 at the MVC layer. Use that to your advantage — let bean absence be the actual gate, and keep the security matcher unconditional.

**How to apply:** any profile-gated endpoint must have BOTH:

1. `@Profile("dev")` (or whichever profile) on the bean — this is the real "does the handler exist" gate.
2. An unconditional `permitAll()` matcher for the URL prefix in `SecurityConfig`, sitting BEFORE the `/api/v1/**` catch-all (FOOTGUNS §B.5: matcher order is first-match-wins).

Document the dual gate in a comment next to the security matcher so the next reviewer doesn't delete the "redundant" permit. See `DevSlSimulateController` and the `/api/v1/dev/**` matcher in `SecurityConfig` for the canonical pattern; `DevSlSimulateBeanProfileTest` pins the bean-absence half of the gate by booting the context under a non-dev profile and asserting the controller field is `null`.

### F.24 `DataIntegrityViolationException` handling belongs in the exception handler, not inline in the service

**Why:** Catching `DataIntegrityViolationException` inside a `@Transactional` service method is awkward in three ways. First, the transaction is already marked rollback-only by the time the catch fires, so the service can't do any cleanup persistence in the same transaction — the recovery path has to spin up a new `REQUIRES_NEW` transaction or punt. Second, every service-layer unit test then has to construct a fake constraint-violation exception with a `ConstraintViolationException` cause carrying the right constraint name, which adds setup boilerplate that bears no resemblance to the real failure mode (a real DB race, not a hand-rolled exception graph). Third, the service method's signature gets cluttered with throws clauses for domain exceptions that the caller can't meaningfully handle anyway.

The cleaner pattern is to let the `DataIntegrityViolationException` bubble out of the service untouched, catch it in the slice's `@RestControllerAdvice`, inspect the constraint name via a small `ConstraintNameExtractor` helper, and rewrap as a domain exception (or rethrow for unknown constraints, falling through to the global 500 handler). The slice-scoped advice is the right place because the constraint-name → domain-exception mapping is slice-specific, and tests can exercise the handler in isolation without touching the service at all.

**How to apply:** when future features need uniqueness-constraint race handling, put the catch in the slice's `@RestControllerAdvice`, never in the service method. Extract constraint-name parsing into a shared helper (see `ConstraintNameExtractor` in `common/`) so each slice only writes the mapping table, not the SQLState walking. See `SlExceptionHandler.handleDataIntegrity` for the canonical shape: a single switch on the extracted constraint name, mapping each known constraint to a slice-specific exception, with an explicit fall-through `throw` for unknown constraints so the global 500 handler still catches genuine bugs.

### F.25 Prod-profile Spring contexts cannot boot inside JUnit tests

**Why:** The `prod` profile has `jwt.secret: ${JWT_SECRET}` with no default — production deploys inject the secret as an environment variable. Tests run in a fresh JVM with no `JWT_SECRET` set, so `@Value` resolution throws at context startup. Even if you set the env var, `application-prod.yml` also has `slpa.sl.trusted-owner-keys: []` (empty), and `SlStartupValidator.check` runs as a `@PostConstruct` on prod and throws `IllegalStateException` on an empty list to prevent prod from booting without real SL keys configured. That's a deliberate fail-fast — but it means any JUnit test annotated `@ActiveProfiles("prod")` blows up in context initialization before a single assertion fires, and the failure looks like an unrelated "context failed to load" stack trace that takes a while to trace back to the profile choice.

**How to apply:** to prove a `@Profile("dev")` bean is absent outside dev, do NOT use `@ActiveProfiles("prod")`. Instead, use the `@Autowired(required = false)` pattern under a neutral test profile (no `@ActiveProfiles` annotation, or `@ActiveProfiles("test")` if you have one). Spring will leave the field `null` if the bean is absent, and you can assert `assertThat(controller).isNull()`. See `DevSlSimulateBeanProfileTest` for the canonical pattern: no profile annotation, `@Autowired(required = false) DevSlSimulateController controller`, and a single assertion that the field is null. This proves bean-absence without requiring the full prod context to boot.

If you genuinely need to test prod-profile behavior end-to-end, the only sane approach is an integration test that runs against a separately-deployed `prod`-profile process (e.g., via Testcontainers), not a `@SpringBootTest` inside the same JVM as the test runner.

### F.26 Slice-scoped `@RestControllerAdvice` ordering needs an explicit `@Order(LOWEST_PRECEDENCE - 100)`

**Why:** A slice handler like `VerificationExceptionHandler` (`@RestControllerAdvice(basePackages = "...verification")`) and `GlobalExceptionHandler` (unscoped) can both match the same exception. At default precedence, Spring resolves ties by bean-registration order, which is **alphabetical**. `GlobalExceptionHandler` (G) happens to sort before `VerificationExceptionHandler` (V), so the global catch-all wins silently and the slice's specific mapping gets shadowed — the response has the global 500 status code instead of the slice's 409. `AuthExceptionHandler` (A) only worked because A sorts before G — alphabetical luck, not a real ordering guarantee, and the next slice whose name starts with H-Z would have hit the same shadow bug.

Caught during Task 2's review: the `VerificationExceptionHandler` was correctly mapping `CodeCollisionException` to 409, but the integration test got a 500 because `GlobalExceptionHandler.handleUncaught` was running first.

**How to apply:** every slice-scoped `@RestControllerAdvice` class in this codebase must carry `@Order(Ordered.LOWEST_PRECEDENCE - 100)` so it runs BEFORE the global catch-all. `GlobalExceptionHandler` carries explicit `@Order(Ordered.LOWEST_PRECEDENCE)` to document its role as the last-resort handler — the explicit annotation makes the precedence relationship visible at the file level rather than buried in alphabetical-order trivia. When you add a new slice in a future epic, match the convention: `@Order(Ordered.LOWEST_PRECEDENCE - 100)` on every slice handler, no exceptions. Do not rely on alphabetical bean-registration order — a future rename can silently break the precedence.

### F.27 `@Transactional` methods that throw after persisting state need `noRollbackFor`

**Why:** Spring's default rollback behavior reverts on any `RuntimeException`. If a `@Transactional` method persists cleanup state (e.g. voiding collision rows, marking a reservation expired, inserting an audit row) and THEN throws a domain exception, those writes get silently rolled back along with the rest of the transaction. The failure mode is invisible in unit tests with mocked repositories because mocks don't exercise the real transaction boundary — the mock records the `save()` call and the assertion passes, even though in production the row would never reach the database. The bug only surfaces when an integration test commits the setup, runs the method under test, and verifies persistence in a fresh transaction.

Caught during Task 2's review: `VerificationCodeService.consume` voided collision rows before throwing `CodeCollisionException`, and the unit tests passed because mocks recorded the `save()`. The integration test that committed real rows showed the void was silently rolled back.

**How to apply:** in any `@Transactional` method that must persist state BEFORE throwing (collision handling, reservation expiry, side-effect audit, etc.):

1. Annotate the method with `@Transactional(noRollbackFor = TheSpecificException.class)`. Use the most specific exception possible — broad `noRollbackFor = RuntimeException.class` would defeat the safety net.
2. Add a load-bearing javadoc comment on the method explaining the annotation must not be removed and pointing to the integration test that pins the contract.
3. Write a `TransactionTemplate`-based integration test that: (a) commits the setup data in transaction A, (b) calls the method under test in transaction B and catches the expected exception, (c) verifies persistence in a fresh transaction C. Mocked unit tests cannot replace this — the real transaction boundary is the thing under test.

See `VerificationCodeService.consume` and `VerificationCodeServiceCollisionIntegrationTest` for the canonical pattern: the service carries `@Transactional(noRollbackFor = CodeCollisionException.class)` with a javadoc note, and the integration test exercises the red-green dance so future refactors can't silently break the guarantee.

### F.28 `MaxUploadSizeExceededException` is thrown before the dispatcher

**Why:** Spring's `StandardServletMultipartResolver` parses the multipart request body before `DispatcherServlet` routes to any `@RestController`. When the request body exceeds `spring.servlet.multipart.max-file-size` or `max-request-size`, the resolver throws `MaxUploadSizeExceededException` during request parsing — which means a package-scoped `@RestControllerAdvice(basePackages = "...")` never sees the exception because the request has not reached any controller in that package yet. A slice's exception handler scoped to `user/` will never handle multipart size errors even on `POST /api/v1/users/me/avatar`. Symptom: the request comes back as a generic 500 from `GlobalExceptionHandler.handleUncaught` (or whatever unscoped catch-all you have) instead of the slice-specific 413 ProblemDetail you wrote.

**How to apply:** put the `MaxUploadSizeExceededException` handler in `common/exception/GlobalExceptionHandler.java` (the unscoped advice) or in any advice without a `basePackages` filter. The `AvatarTooLargeException` defensive re-check in `AvatarService.upload` can still live in `UserExceptionHandler` because it fires from inside a controller method. Keep both handlers producing the same `ProblemDetail` shape (same `type`, `title`, `detail`, `code`) so clients cannot distinguish which layer caught the bloat — the dispatcher-layer one carries the generic "Upload too large" detail, the service-layer one carries the same. See `GlobalExceptionHandler.handleMaxUploadSizeExceeded` and `UserExceptionHandler.handleAvatarTooLarge` for the canonical pair.

### F.29 Spring `@Transactional` same-class method calls bypass the AOP proxy

**Why:** Spring's default `@Transactional` mechanism uses JDK dynamic proxies or CGLIB subclassing to intercept method calls. A method call through a bean reference goes through the proxy and triggers the transaction interceptor. But a same-class call — `this.helper()` from inside another method on the same class — goes directly to the target object and **bypasses the proxy entirely**. This holds regardless of the target method's visibility (private, protected, or public). It is not a bug in your code; it is a fundamental limitation of proxy-based AOP. The trap: you add `@Transactional` to a private helper, call it from a non-`@Transactional` public method, and observe that JPA dirty checking never flushes the update. No error is thrown. The test passes in isolation (via direct service injection + stubbed repository) but fails in integration.

**How to apply:** to narrow a `@Transactional` boundary, you have three options: (a) extract the DB mutation to a separate `@Service` bean and inject it (proxy fires correctly through the injected reference); (b) self-inject via `@Autowired @Lazy AvatarService self` and call `self.helper()` (the `@Lazy` breaks the circular dependency); (c) use `TransactionTemplate` programmatically to open an explicit transaction around a lambda. If the narrowing is not worth the extra plumbing, keep the annotation on the outer public method. `AvatarService.upload` in Epic 02 sub-spec 2a accepts the broader boundary — the single `@Transactional` on `upload()` spans the S3 puts because the Phase 1 scale argument beats the optimization argument. Revisit if profiling ever shows DB pool starvation.

### F.30 `@JsonIgnoreProperties(ignoreUnknown = false)` is a privilege-escalation guard that must have test coverage

**Why:** by default, Spring's Jackson configuration silently ignores unknown JSON fields when deserializing a request body into a DTO — `{"email": "hacker@example.com", "role": "admin"}` becomes an object with only the fields the DTO declares, and the extras are discarded. This is usually fine (forward-compatible APIs). It is **not** fine on endpoints where the DTO intentionally omits fields to prevent clients from setting them (e.g. `UpdateUserRequest` omits `email`, `role`, `verified` by design). Without strict-mode Jackson on the DTO, a hostile client can smuggle those fields into the request body and rely on Jackson's leniency to get them past validation. Even WITH the guard, a future refactor can silently remove the annotation ("cleanup: delete unused annotation") and reopen the hole.

**How to apply:** every DTO that needs field-injection protection gets `@JsonIgnoreProperties(ignoreUnknown = false)` AND the global `spring.jackson.deserialization.fail-on-unknown-properties: true` is turned on in `application.yml` (see F.31 for why the annotation alone is a no-op in Spring Boot 4 / Jackson 3). Every such DTO also gets a dedicated security canary test that posts a request body with an unknown field and asserts 400 with the `user/unknown-field` ProblemDetail type. Example: `UserControllerUpdateMeSliceTest.put_me_rejectsUnknownFields_returns400` in the `user/` slice. **Scoping note:** the `HttpMessageNotReadableException` handler (which unwraps Jackson's `UnrecognizedPropertyException`) currently lives in `UserExceptionHandler` (scoped to the `user/` package). If a future slice (e.g. `auction/`, `parcel/`) adopts the same hardening, that slice's `UnrecognizedPropertyException` will fall through to `GlobalExceptionHandler` and get a generic 400 instead of the tailored "unknown field" response. When that happens, either (a) add a sibling handler to the new slice's advice, or (b) move the handler up to `GlobalExceptionHandler` so every slice benefits. Option (b) is the cleaner long-term fix.

### F.31 In Spring Boot 4 / Jackson 3, `@JsonIgnoreProperties(ignoreUnknown = false)` is a no-op without the global flag

**Why:** Jackson 3 changed the default of `DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES` from `true` to `false`. In Jackson 2 + Spring Boot 3 the per-class annotation `@JsonIgnoreProperties(ignoreUnknown = false)` was enough to re-enable strict checking on a specific DTO because the global default was "strict" anyway. In Jackson 3 + Spring Boot 4, the per-class annotation ALONE does not fire — the ObjectMapper's global default is now "lenient", and the annotation only flips the feature on a class that Jackson chose to consult, which in practice means the annotation is ignored for request-body deserialization unless you also flip the global feature. The failure mode is silent: your canary test passes against your hand-rolled ObjectMapper (which you probably configured strict for assertions), but a real HTTP request with an unknown field gets through without error because the Spring-managed ObjectMapper is still lenient.

Discovered during Task 4a when the first cut of `UpdateUserRequest` carried only the annotation and the slice test passed against a bare MockMvc setup but the integration test against the full Spring Jackson stack silently accepted the hostile field. Took an hour to trace because Jackson 3 released in 2025 and most StackOverflow answers still assume Jackson 2 behavior.

**How to apply:** every Spring Boot 4 service that wants strict JSON handling must set `spring.jackson.deserialization.fail-on-unknown-properties: true` in `application.yml` (or the equivalent `Jackson2ObjectMapperBuilderCustomizer` bean) AND keep `@JsonIgnoreProperties(ignoreUnknown = false)` on the DTOs for documentation + defense-in-depth. The global flag is what actually does the work; the annotation is a comment that a reviewer will notice. If you ever need a DTO to opt OUT of the strict default (e.g. a webhook body where upstream adds new fields), flip the DTO-level annotation to `@JsonIgnoreProperties(ignoreUnknown = true)` and the per-class escape hatch wins over the global default.

### F.32 Spring Boot 4 ships Jackson 3 — the databind exception classes moved to `tools.jackson.databind.exc.*`

**Why:** Jackson 3's top-level API package rename split the code across two Maven coordinates: annotations stayed in `com.fasterxml.jackson.annotation.*` (so `@JsonIgnoreProperties`, `@JsonProperty`, etc. keep the old import), but the runtime classes — `JsonMappingException`, `UnrecognizedPropertyException`, `MismatchedInputException`, `InvalidFormatException`, and friends — all moved to `tools.jackson.databind.exc.*`. Importing `com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException` compiles against the Jackson 2 artifact if it is on the classpath for some other reason (test-only, transitive, etc.) and then fails at runtime with `NoClassDefFoundError` or never catches the exception because the type is a different class from the one Spring actually throws.

Discovered during Task 4a when `UserExceptionHandler.handleHttpMessageNotReadable` was imported from `com.fasterxml.jackson.databind.exc.*` and the handler's `instanceof` check silently never fired (the real exception was a `tools.jackson.databind.exc.UnrecognizedPropertyException`, a different class with a matching simple name). IDE auto-import picked the wrong package. The slice test failed with a generic 400 instead of the slice's tailored `user/unknown-field` ProblemDetail.

**How to apply:** in any Spring Boot 4 codebase, when you need a Jackson databind runtime class, import from `tools.jackson.databind.exc.*`. Annotations still come from `com.fasterxml.jackson.annotation.*`. If you see `com.fasterxml.jackson.databind.*` imports (not `.annotation.` or `.annotation`), they are from Jackson 2 and will either miss at runtime or type-mismatch in `instanceof` checks. Clean them up in the same commit that touches the file.

### F.33 Spring wraps Jackson deserialization errors in `HttpMessageNotReadableException`

**Why:** when a controller's `@RequestBody` deserialization fails (unknown field, type mismatch, malformed JSON), the actual Jackson exception — e.g. `UnrecognizedPropertyException`, `MismatchedInputException` — is thrown inside `MappingJackson2HttpMessageConverter.read(...)`. Spring catches that exception and rethrows it wrapped in `org.springframework.http.converter.HttpMessageNotReadableException` before the exception propagates out to any `@RestControllerAdvice`. So a handler annotated `@ExceptionHandler(UnrecognizedPropertyException.class)` NEVER FIRES — by the time the advice machinery runs, the exception type is `HttpMessageNotReadableException` and its `getCause()` is the Jackson type. Subscribing to the Jackson type directly makes the handler dead code.

Discovered during Task 4a: the first cut of `UserExceptionHandler.handleUnknownField` was annotated `@ExceptionHandler(UnrecognizedPropertyException.class)` and never fired in the slice test. After stepping through the MVC machinery it was obvious — Spring's own handler chain had already wrapped the cause.

**How to apply:** handlers that want to respond to Jackson deserialization errors must subscribe to `HttpMessageNotReadableException` and call `getCause()` + `instanceof` to detect the underlying Jackson type. See `UserExceptionHandler.handleHttpMessageNotReadable` for the canonical shape: one handler on the wrapper, a switch on `getCause()` for the three Jackson types we care about (`UnrecognizedPropertyException` → 400 `user/unknown-field`, everything else → fall through to the generic 400 malformed-body response).

### F.34 `StorageConfigProperties.bucket` is a required bind-time field — every `@SpringBootTest` implicitly needs `slpa.storage.bucket` set

**Why:** `StorageConfigProperties` uses `@NotBlank` + constructor binding on the `bucket` field because a missing bucket in prod is a deploy mistake that must fail fast at startup. The downside: every `@SpringBootTest` that boots the full context implicitly requires `slpa.storage.bucket` to be non-empty, because Spring's property binder runs before the test's `@TestPropertySource` can intervene. Today the dev `application.yml` supplies `slpa-uploads` (via docker-compose + `.env`) so the test suite loads the dev profile and gets the property by coincidence — but a future test written with `@TestPropertySource(properties = "spring.profiles.active=")` (or equivalent profile-less boot) will crash on context load with a `BindException: Field error in object 'slpa.storage' on field 'bucket': rejected value []`.

Not a bug today, but a latent coupling: the test suite is relying on application.yml leaking into every test context to satisfy a required config property. Task 2's review noted this and punted to Task 6.

**How to apply:** when writing a new `@SpringBootTest` that tweaks the active profile, always include `slpa.storage.bucket=test-bucket` (or whatever filler) in `@TestPropertySource` so the binder has a value regardless of which application yaml loads. If the footgun eventually causes enough friction, relax `@NotBlank` to optional with a `StorageStartupValidator` runtime check instead — the runtime check can use profile detection to skip the validation in test profiles, but the bind-time constraint cannot.

### F.35 `StorageStartupValidator` profile detection treats every non-`prod` profile as "auto-create bucket"

**Why:** the startup validator decides between "fail fast if bucket is missing" (prod) and "auto-create the bucket" (everything else) with a simple `Arrays.asList(environment.getActiveProfiles()).contains("prod")` check. This is fine today — the project only has `dev`, `test`, and `prod` profiles, and the binary choice is correct for all three. The footgun lights up the day someone adds a fourth profile (`staging`, `integration`, `canary`, `demo`) and expects it to behave like prod by default. The new profile silently auto-creates buckets in whatever environment it points at, and a misconfigured `slpa.storage.bucket` in staging could auto-create a brand-new empty bucket next to the real one and start writing avatars to it with no alert.

Task 2's review noted this and deferred the fix — the profile matrix is small enough today that the risk is zero, but the pattern is not future-proof.

**How to apply:** when a fourth profile is introduced, replace the `contains("prod")` check with an allow-list of "auto-create OK" profiles (`dev`, `test`) and a default-deny for anything else. The default-deny matches the safer "prod semantics for unknown profiles" posture. See `StorageStartupValidator.afterApplicationReady` for the current implementation — the fix is one line (plus a test that boots under `@ActiveProfiles("staging")` and asserts the validator threw).

### F.36 MockMvc's `multipart()` builder injects a pre-built `MockMultipartFile` that bypasses `StandardServletMultipartResolver`

**Why:** MockMvc's `MockMvcRequestBuilders.multipart(url).file(MockMultipartFile)` takes a pre-built `MockMultipartFile` and injects it directly into the request as an already-parsed `MultipartFile`. The real `StandardServletMultipartResolver` is never invoked because MockMvc skips the servlet container's multipart parsing stage. As a consequence, `spring.servlet.multipart.max-file-size` and `max-request-size` are NOT enforced inside slice tests — a 10MB `MockMultipartFile` against an endpoint with a 2MB limit will happily reach the controller, and the only size enforcement that fires is whatever the service method does defensively (e.g. `AvatarService.upload` re-checking `file.getSize() > limit`). Tests that want to pin `MaxUploadSizeExceededException` — the exception Spring's real dispatcher would throw — simply cannot reproduce that path via MockMvc.

Discovered during Task 4b: a slice test tried to assert that a 3MB upload returned 413, and the test passed for the wrong reason (it was the service-layer re-check firing, not the dispatcher-layer `MaxUploadSizeExceededException` that the `GlobalExceptionHandler.handleMaxUploadSizeExceeded` method is written to catch). The `GlobalExceptionHandler` code path has ZERO test coverage from slice tests, period.

**How to apply:** (a) keep the defensive service-layer size check so MockMvc slice tests can exercise the 413 path end-to-end without involving the real multipart resolver; (b) if you want to pin the dispatcher-layer `MaxUploadSizeExceededException` path, use a real-HTTP integration test client (`TestRestTemplate`, `WebTestClient`, or Spring Boot's `@AutoConfigureWebTestClient`) that posts a real `multipart/form-data` body through the real servlet stack. The `AvatarUploadFlowIntegrationTest` from Task 5 runs against real dev MinIO with the full multipart pipeline and is the right place to pin that behavior if it ever becomes load-bearing. Until then, document the gap in a comment on the slice test so the next reader does not assume the dispatcher code is covered.

### F.37 Next.js 16 route groups: `(verified)/` doesn't appear in the URL

**Why:** Next.js route groups (directories wrapped in parentheses) exist for layout nesting only — the parenthesized segment is stripped from the URL path. `app/dashboard/(verified)/overview/page.tsx` serves at `/dashboard/overview`, not `/dashboard/(verified)/overview`. The access gate lives in the route group's `layout.tsx`, which checks `user.verified` and redirects unverified users to `/dashboard/verify`. Adding a new verified-only tab is a drop-in under `(verified)/` — no URL or gate changes needed.

**How to apply:** when creating a new dashboard tab that requires verification (e.g. `/dashboard/settings`), add `app/dashboard/(verified)/settings/page.tsx`. The existing `(verified)/layout.tsx` gate applies automatically. Do NOT add an `(unverified)/` route group — the verification takeover is handled by the parent `dashboard/layout.tsx` which redirects unverified users to `/dashboard/verify` before the `(verified)` layout ever mounts. If you accidentally nest a page outside the `(verified)/` group but inside `dashboard/`, it will be accessible to unverified users with no gate.

### F.38 TanStack Query `refetchInterval` pauses when the tab is backgrounded

**Why:** `useCurrentUser({ refetchInterval: 5000 })` polls the backend every 5 seconds so the verification flow can auto-detect when the user completes in-world verification. By default, TanStack Query sets `refetchIntervalInBackground: false`, which means polling pauses when the browser tab loses focus (the `visibilitychange` event). Polling resumes immediately on tab focus via `refetchOnWindowFocus: true`. This is the correct behavior — polling a backgrounded tab wastes bandwidth and battery for no visible benefit — but it can confuse testers who switch to the SL viewer, complete verification, switch back, and see a 0-5 second delay before the dashboard transitions.

**How to apply:** do not set `refetchIntervalInBackground: true` to "fix" the perceived delay. The existing `refetchOnWindowFocus: true` already fires an immediate refetch when the user returns to the browser tab. If the delay is still a UX concern, the manual "refresh my status" button on the verify page fires `refetch()` immediately. In integration tests with `vi.useFakeTimers`, note that `advanceTimersByTime(5100)` simulates the poll interval but does not simulate the visibility API — jsdom always reports `document.visibilityState === "visible"`, so tests never exercise the pause/resume path.

### F.39 `Avatar cacheBust` requires the backend to project `updatedAt` into the DTO

**Why:** `<Avatar src={url} cacheBust={user.updatedAt} />` appends `?v={timestamp}` to the avatar URL so the browser fetches a fresh image after an upload instead of serving the stale cached version. This pattern only works if the backend includes `updatedAt` in the `UserResponse` DTO — without it, `cacheBust` is always `undefined` and the browser happily serves the old avatar from its HTTP cache (the proxy sets `Cache-Control: public, max-age=86400, immutable`). The backend touch-up that added `updatedAt` to `UserResponse` was part of Epic 02 sub-spec 2b Task 1; if a future DTO refactor drops the field, avatar updates will silently appear broken until a hard refresh.

**How to apply:** when modifying `UserResponse` or `CurrentUser` DTOs, keep `updatedAt` projected. If a different cache-busting strategy is adopted (e.g. content-hash URLs from S3 ETags), remove `cacheBust` from `Avatar` at the same time so there is no dangling prop.

### F.40 Toast portal hydration guard: `createPortal(..., document.body)` needs the `mounted` state flip

**Why:** `ToastProvider` renders its notification stack via `createPortal(children, document.body)`. During server-side rendering (or static generation), `document.body` does not exist and `createPortal` throws. The fix is the standard Next.js hydration guard: a `mounted` state initialized to `false`, flipped to `true` in a `useEffect`, with the portal conditional on `mounted`. This is the same pattern used by `ThemeToggle` and any component that touches browser-only APIs. The React 19 + Next.js 16 `react-hooks/set-state-in-effect` lint rule flags this pattern as a warning because it triggers a cascading render, but the render is intentional and unavoidable — there is no way to know we are in the browser without an effect.

**How to apply:** any new component that uses `createPortal` or accesses `document` / `window` at render time needs this guard. Copy the pattern from `ToastProvider.tsx` or `ThemeToggle.tsx`. Do not suppress the lint rule globally — the warning is correct for 99% of cases; these hydration guards are the 1% exception.

### F.41 JSDOM does not support native drag-and-drop: test file inputs via the change event

**Why:** `ProfilePictureUploader` supports both drag-and-drop (`onDragOver` / `onDrop`) and a hidden `<input type="file">` click path. JSDOM's `DragEvent` and `DataTransfer` constructors do not round-trip `files` — `new DataTransfer()` exists but `dataTransfer.files` is always empty after assignment in jsdom. This means `fireEvent.drop(element, { dataTransfer: { files: [file] } })` silently delivers an empty file list to the handler and the test asserts against the "no file" path instead of the upload path. The test passes for the wrong reason.

**How to apply:** in vitest/jsdom tests, exercise the file-selection path by firing a `change` event on the hidden `<input type="file">` element: `fireEvent.change(input, { target: { files: [file] } })`. This works because jsdom supports `input.files` assignment. Reserve drag-and-drop assertions for browser-level smoke tests (Playwright, Cypress) where the real `DataTransfer` API is available. If you need to unit-test the drop handler logic in isolation, extract it into a pure function that takes a `FileList` and test the function directly.

### F.42 Continent bounding boxes are static data — annual review is the update cadence

**Why:** `MainlandContinents` embeds 17 axis-aligned bounding boxes sourced from the Second Life wiki's `ContinentDetector` page (snapshot taken 2026-04-16). The SL Grid Survey API would be the "live" data source, but it is unreliable (frequent 5xx, no SLA, and Linden has not committed to keeping it running). We intentionally trade freshness for availability: the grid layout has been effectively frozen for years, and the cost of a missed continent addition is a few sellers being told "not on Mainland" when they are — a recoverable UX glitch, not data loss.

**How to apply:** when Linden adds a new Mainland continent (or extends an existing one) the `MainlandContinents` constant table must be updated manually. Add an annual task to the ops calendar to diff the wiki page against `MainlandContinents.CONTINENTS`. Do not re-introduce a Grid Survey API dependency — if freshness matters enough to justify a live call, the right move is a cached + circuit-broken adapter, not a direct call.

### F.43 Flyway disabled in Phase 1 — entities are the schema source of truth

**Why:** The `V1__*.sql` and `V2__*.sql` Flyway migrations were deleted during Epic 03 sub-spec 1. Phase 1 uses `spring.jpa.hibernate.ddl-auto: update` in dev + prod while the schema stabilizes. This means: (a) any entity field or annotation change IS the schema change — no separate migration file exists; (b) DROP COLUMN / RENAME / narrowing NOT NULL are unsafe via Hibernate DDL-auto and must be applied manually via `psql` or a one-off `ALTER TABLE`; (c) on a fresh Postgres, running `./mvnw spring-boot:run -Dspring-boot.run.profiles=dev` creates every table from entities on startup. On existing dev DBs with an `flyway_schema_history` row, the table is harmless and can be dropped.

**How to apply:** when adding a new column, do it via a `@Column` on the entity — Hibernate adds it on next boot. When dropping or renaming, add a manual `ALTER TABLE` to the team's shared dev-ops notes and run it before the PR lands so every developer's local DB stays in sync. Before production deployment (future Phase 2 ops work), swap `ddl-auto: validate` + re-introduce Flyway with a baseline migration generated from the current Hibernate schema. Do not re-add `ddl-auto: update` in prod — it is a tripwire, not a migration strategy.

### F.44 `PublicAuctionStatus` enum is a compile-time privacy boundary

**Why:** `AuctionDtoMapper.toPublicStatus(AuctionStatus)` is an exhaustive `switch` that maps the 9-value `AuctionStatus` to the 2-value `PublicAuctionStatus { ACTIVE, ENDED }`. If a new `AuctionStatus` is added without mapping it, Java's pattern-exhaustiveness check fires a compile error — you cannot forget. The four terminal statuses (COMPLETED / CANCELLED / EXPIRED / DISPUTED) all collapse to `ENDED` so non-sellers cannot distinguish them — this is a deliberate privacy guarantee (bidders learning "this auction was cancelled, not completed" leaks seller intent). The four pre-ACTIVE statuses (DRAFT / DRAFT_PAID / VERIFICATION_PENDING / VERIFICATION_FAILED) throw `IllegalStateException` from the mapper because `AuctionController.get()` is responsible for 404-hiding those from non-sellers before the mapper is ever called.

**How to apply:** when adding a new `AuctionStatus` value, the compiler will force you into `toPublicStatus`. Ask: can a non-seller see this, and if so, should they see ACTIVE or ENDED? Do not add a third `PublicAuctionStatus` value without a full spec review — the two-value constraint is what makes the privacy boundary auditable. `AuctionControllerIntegrationTest.get_cancelledAsNonSeller_returnsEndedWithoutWinnerOrFeeFields` and `FullFlowSmokeTest.visibility_sellerGetsFullView_publicGetsCollapsedView` pin this behavior — do not weaken them to make a new status "convenient".

### F.45 Parcel locking has two independent layers — bypass either and duplicate ACTIVE auctions become possible

**Why:** Only one auction per parcel can be in a "locking" status (`ACTIVE` / `VERIFICATION_PENDING`) at a time. This is enforced in two places: (1) a service-layer pre-check `AuctionRepository.existsByParcelIdAndStatusInAndIdNot` called from `AuctionVerificationService` + `SlParcelVerifyService` + `BotTaskService.complete`, throwing `ParcelAlreadyListedException(parcelId, blockingAuctionId)`; (2) a Postgres partial unique index `uq_auctions_parcel_locked_status ON auctions(parcel_id) WHERE status IN ('ACTIVE', 'VERIFICATION_PENDING')` created at boot by `ParcelLockingIndexInitializer`, surfacing as `DataIntegrityViolationException` → `ParcelAlreadyListedException(parcelId, -1L)` (the sentinel `-1L` means "race-caught, blocker ID unavailable at catch-time"). Both layers are load-bearing: the service check short-circuits the fast path and gives a useful error message with the blocker ID; the DB index catches the concurrent-verify race where two transactions both pass the pre-check then try to save simultaneously.

**How to apply:** never write `auctionRepo.save(a)` with `a.status = ACTIVE` without the partial-unique-index backstop path — always go through `saveAndFlush` inside a try/catch that maps `DataIntegrityViolationException` with constraint name `uq_auctions_parcel_locked_status` to `ParcelAlreadyListedException(-1L)`. Direct database writes (e.g. a future admin tool or data migration) that bypass both layers can create duplicate ACTIVE auctions. `ParcelLockingRaceIntegrationTest` is the canary — do not delete it, do not weaken it to a mock-based test.

### F.46 Bot endpoints ship without authentication in sub-spec 1 — Epic 06 MUST add it

**Why:** `SecurityConfig` has `permitAll` on `GET /api/v1/bot/tasks/pending` and `PUT /api/v1/bot/tasks/{taskId}` because the SL bot worker (Epic 06) does not exist yet, and inventing a bot-auth scheme without a worker implementation to validate against would be premature. The validation that DOES happen is body-level: `authBuyerId == slpa.bot-task.primary-escrow-uuid` and `salePrice == sentinelPrice` must both match, so an attacker cannot simply call `PUT /bot/tasks/{id}` with arbitrary data — but they CAN race the real worker to claim a task, or flip auction states via FAILURE callbacks. This is an acceptable short-term tradeoff only because no real bot is running yet.

**How to apply:** Epic 06 MUST add bot worker authentication (mTLS or bearer token — pick one in the Epic 06 spec) before the real worker deploys. The `DEFERRED_WORK.md` entry "Bot service authentication (Epic 06)" is the forcing function — do not close that entry until real auth is wired. Until then, `/api/v1/bot/tasks/**` is a locally-trusted attack surface and the bot worker must be a localhost-or-private-network-only deployment.

### F.47 `/sl/parcel/verify` and `/sl/verify` trust SL headers — Linden's proxy is the trust boundary

**Why:** Both endpoints are `permitAll` at Spring Security because an in-world LSL script cannot present a JWT (SL avatars are not web sessions). The trust comes from `X-SecondLife-Shard: Production` and `X-SecondLife-Owner-Key: <uuid>` headers that Linden's grid proxy injects on outbound `llHTTPRequest` calls — these headers CANNOT be set by client code inside LSL, so an attacker outside the grid cannot forge them as long as the request reaches us via the real SL proxy. `SlHeaderValidator` checks both: shard matches `slpa.sl.expected-shard` and owner-key is in `slpa.sl.trusted-owner-keys` (the list of UUIDs that own the authorized in-world scripted terminals).

**How to apply:** local dev + Postman testing REQUIRES manually setting the SL headers — there is no backdoor. The Postman collection + `DevSlSimulateController` + integration tests all set the dev-placeholder `X-SecondLife-Owner-Key: 00000000-0000-0000-0000-000000000001` explicitly. Production deployment MUST override `slpa.sl.trusted-owner-keys` with the real script-owner UUIDs via env var; `SlStartupValidator` fails fast on prod boot if the list is empty. Do not log the headers — they are not secrets per se, but logging them invites someone to "helpfully" grep the logs for UUIDs to "debug" and accidentally publish the trust anchor.

### F.48 WebClient retry policy covers 5xx + network errors only — 4xx is not retried

**Why:** Both `SlWorldApiClient` and `SlMapApiClient` use a WebFlux `retryWhen(Retry.backoff(...).filter(is5xxOrNetwork))` loop. This is deliberate: 5xx + network errors are transient (retry makes sense), 4xx errors are terminal (retry will not change the outcome, and for 429 rate-limit it would make things worse). But it means the callers see fast-failure on any 4xx from the SL API — including `429 Too Many Requests` if Linden ever imposes rate limits. In the current implementation, `ExternalApiTimeoutException` (→ HTTP 504) is the catch-all for "SL API failed"; a 429 would surface as a 504 to the end user, which is misleading.

**How to apply:** if/when we see real 429s in production logs, add a specific `Retry-After`-honoring retry path for 429 — but do NOT extend the existing generic retry loop to cover all 4xx (that would retry 400/404/422 and burn retries on terminal errors). The right pattern is a second `retryWhen` layered before the main one that matches only 429 and respects the `Retry-After` header. Track this in `DEFERRED_WORK.md` when it surfaces — it is not a day-one concern because Linden has not published rate limits on the World/Map APIs we use.

### F.49 Photo GET bytes is public but respects auction visibility

**Why:** `GET /api/v1/auctions/{id}/photos/{photoId}/bytes` is `permitAll` so the frontend can render `<img>` tags without proxying through the API client. But the handler enforces that pre-ACTIVE auctions (DRAFT / DRAFT_PAID / VERIFICATION_PENDING / VERIFICATION_FAILED) return 404 to non-sellers — the seller must be the authenticated caller to fetch their own draft photos. If this check is removed or weakened, an attacker who guesses photo IDs can enumerate draft listings before they go live, leaking seller intent.

**How to apply:** the visibility check lives in `AuctionPhotoService.getBytes` (or the controller — check which). When modifying photo endpoints, run the `AuctionPhotoControllerIntegrationTest` public-GET-bytes case to verify the 404-hides-pre-ACTIVE path still fires. Do not refactor the public bytes proxy into a pure "stream whatever is at this MinIO key" endpoint — the auction-status check is the access control.

### F.50 Jackson `fail-on-unknown-properties: true` is global — extra fields in any request body return 400

**Why:** `application.yml` sets `spring.jackson.deserialization.fail-on-unknown-properties: true` globally (Epic 02 added it as a privilege-escalation guard for `PUT /api/v1/users/me`). Every request DTO in the codebase inherits this — sending a field Jackson does not recognize returns 400 with `HttpMessageNotReadableException` (mapped to a ProblemDetail by `UserExceptionHandler` for user endpoints, `GlobalExceptionHandler` for everything else). This is desirable for the fail-closed security posture but means clients that send "extra just in case" fields will see 400s.

**How to apply:** when clients report "my request worked yesterday, now 400 with USER_UNKNOWN_FIELD or similar", check the diff for a newly-added field they might be sending that the server renamed or removed. Do not disable the global flag to "make the error go away" — the flag is a load-bearing canary (see `UserControllerUpdateMeSliceTest.put_me_rejectsUnknownFields_returns400`). If a specific DTO genuinely needs to tolerate extra fields (e.g., webhook-style payloads from a third party), annotate THAT specific type with `@JsonIgnoreProperties(ignoreUnknown = true)` — do not flip the global.

### F.51 Adding an `AuctionStatus` enum value requires refreshing the Postgres CHECK constraint — `ddl-auto` does not

**Why:** Hibernate creates an `auctions_status_check` CHECK constraint listing every enum value at first-boot, but `ddl-auto=update` does NOT rewrite that constraint when you add a new value in a later release. Adding `SUSPENDED` (sub-spec 2 Task 4) without a refresh step means the column type accepts `SUSPENDED` but the CHECK constraint rejects it — every INSERT/UPDATE carrying the new value fails with `violates check constraint "auctions_status_check"` and the failure is often caught silently inside a `@Transactional` method that just rolls back. The test suite catches it because the constraint-rejection happens before you return a DTO; production would manifest as "suspending this listing didn't save but returned 200". The companion to F.43 (Flyway disabled — entities are the schema source of truth): when the schema source is entities, the schema refresh has to run on boot, and for CHECK constraints that means DDL, not Hibernate.

**How to apply:** when adding a new `AuctionStatus` value, update the DDL in `AuctionStatusCheckConstraintInitializer` to list every enum value in the new canonical order. The initializer drops the constraint and re-adds it at every `ApplicationReadyEvent`, which is idempotent — first boot after a deploy picks up the new value, subsequent boots are no-ops. The same pattern applies to any other enum-backed column with a CHECK constraint: `bot_tasks.status`, `cancellation_logs.from_status`, etc. When a new status lands, touch the initializer in the same commit as the enum change — there is no "I'll add it in the next PR" that works here because the first deploy between the two commits will break on every write of the new status. `AuctionVerificationServiceMethodATest` + the ownership-monitor integration tests pin the constraint refresh by actually persisting `SUSPENDED` rows; do not relax them to mock-only tests.

### F.52 React 19 strict mode forbids `Date.now()` reads during render — use a `useState` initializer

**Why:** React 19's strict-mode purity check fires on any `Date.now()` / `Math.random()` call that executes during the render phase, under the principle "renders must be pure". The lint rule `react-hooks/purity` (new in React 19 plugin) flags `const expired = Date.now() > expiresAt.getTime()` inline in a component body as a violation. The failure mode is not a crash — it's a noisy dev-only warning and, more insidiously, render-phase state that silently diverges across strict-mode's double-render during development. The first render and second render disagree because `Date.now()` advances, and the `useState` initialization that reads it gets different values on the two passes.

Discovered in sub-spec 2 Task 9 when `VerificationMethodRezzable` tried to seed its `expired` state inline from `expiresAt <= Date.now()` — the lint failed the build and stepping through with a slowed clock showed the two strict-mode renders disagreeing.

**How to apply:** any component-initial state derived from `Date.now()` (expiry flags, countdown anchors, stable "mounted at" timestamps) must be computed inside a `useState` initializer function — the initializer runs BEFORE the render phase, not during it, and strict mode does not double-invoke it. Example: `const [expired, setExpired] = useState<boolean>(() => expiresAt ? expiresAt.getTime() <= Date.now() : false);`. For advancing state (e.g. the "expiry has now occurred" transition), schedule a `setTimeout` in a `useEffect` and flip the state from the effect callback. See `VerificationMethodRezzable.tsx` for the canonical pattern. If you need a stable reference timestamp that never updates (e.g. "page loaded at"), combine `useState(() => Date.now())` with an empty dependency array — the initializer runs once, the value is stable across renders. Do not reach for `useMemo(() => Date.now(), [])` as a workaround — `useMemo` may recompute on dep changes you didn't expect, and its reads still count as render-phase.

### F.53 `sessionStorage.setItem` on every keystroke is expensive — debounce the persistence

**Why:** `useListingDraft` persists the entire draft-state object to `sessionStorage` so a tab close / refresh doesn't nuke in-progress wizard work. The naive version writes on every state change — including every keystroke in the `sellerDesc` textarea — and `sessionStorage.setItem` is a synchronous operation that JSON-serializes the whole object each time. On a complex draft (50+ tags, 10 photo refs, long description) this is visibly laggy in production builds on slower machines and a flamegraph hotspot in profiling. The sync call is also a main-thread blocker, so other keystrokes queued during the serialize-and-persist stall and the input feels "sticky".

**How to apply:** wrap the persist call in a debounced effect — sub-spec 2's `useListingDraft.ts` uses a 150ms debounce via `setTimeout`/`clearTimeout` in an effect that resets the timer on every state change, so only the last value in a rapid burst actually hits `sessionStorage`. Also register an unmount flush so the tail of a burst still persists when the component tears down before the timer fires: capture the latest state in a ref, then write synchronously from a final `useEffect(() => () => { writeNow(ref.current); }, [])`. Do NOT try to throttle per-input (e.g. one timer per field) — the composed state is one JSON blob and the expensive path is the serialization, not the identity of the field that changed. Do NOT set a shorter debounce than ~100ms thinking "it feels more live" — the user-visible difference is zero (sessionStorage is only read on mount) and the cost grows linearly with the debounce rate.

### F.54 Hibernate `ddl-auto: update` does not backfill DB-level defaults — persist a `columnDefinition` default for new NOT NULL columns

**Why:** adding a new `@NotNull` column to an existing entity via `ddl-auto: update` adds the column (Hibernate issues `ALTER TABLE ADD COLUMN`) but does NOT add a DB-level `DEFAULT 0` clause — Hibernate's null-safety is enforced by the entity builder setting the Java field, not by the column DDL. On a fresh dev DB with no prior rows this works. On an existing dev DB with pre-existing `auctions` rows, the `ALTER TABLE ADD COLUMN ... NOT NULL` fails because Postgres cannot find a value for the existing rows. Hibernate's default in this case is to add the column as nullable, leaving a latent footgun: reads that hit pre-existing rows see `null` and the Integer-to-int unboxing in service code NPEs at runtime with no hint about why.

Discovered during sub-spec 2 Task 4 when `Auction.consecutiveWorldApiFailures` (Integer, NOT NULL, @Builder.Default = 0) was added — the entity-side default covered new rows created after the deploy, but any existing auction row had `NULL` in the new column and `OwnershipCheckTask` threw NPE the first time it ran.

**How to apply:** for any new NOT NULL column added to an existing entity, set both the entity-side default AND a `columnDefinition` that Hibernate emits verbatim as part of the `ALTER TABLE` statement. The pattern:
```java
@Builder.Default
@Column(name = "consecutive_world_api_failures", nullable = false,
        columnDefinition = "integer NOT NULL DEFAULT 0")
private Integer consecutiveWorldApiFailures = 0;
```
The `columnDefinition` override makes Hibernate emit `ALTER TABLE auctions ADD COLUMN consecutive_world_api_failures integer NOT NULL DEFAULT 0`, which Postgres fills for existing rows using the default. Drop the `DEFAULT` clause from `columnDefinition` on the next deploy if you want to tighten the app-layer contract ("new rows must always set this explicitly"); Postgres keeps the filled-in values for existing rows once they're there. Do NOT rely on a migration / initializer to UPDATE the pre-existing rows — it works for tables the team controls but is racy if the upgrade runs while other pods are writing, and it's a maintenance burden that compounds with every new column. The `columnDefinition` path is a one-line fix in the entity.

### F.55 Hibernate `@JdbcTypeCode(SqlTypes.JSON)` maps to Postgres `jsonb` — the column is Postgres-specific

**Why:** `FraudFlag.evidenceJson` is a free-form `Map<String, Object>` serialized to a `jsonb` column via `@JdbcTypeCode(SqlTypes.JSON) + columnDefinition = "jsonb"`. This is a deliberate schema choice: evidence payloads are a grab-bag of fields (new World API response, detected owner UUID, consecutive-failure count at time of detection, etc.) that evolve as new fraud signals are added, and a typed column-per-field explosion is worse than a `jsonb` blob queried via `@>` operators from the future Epic 10 admin dashboard. The tradeoff: the column is Postgres-specific. H2 / MySQL / SQLite do not have a native `jsonb` type, and Hibernate's `SqlTypes.JSON` either falls back to `text` (loses query-ability) or fails on DDL generation depending on the dialect. For this project, that is fine — prod is Postgres, tests run against Testcontainers Postgres (or the dev Postgres when running locally), and `H2 mode` is not part of the test strategy.

**How to apply:** when adding a new `jsonb` column, always pair `@JdbcTypeCode(SqlTypes.JSON)` with `columnDefinition = "jsonb"` — without the override, Hibernate falls back to its dialect-default JSON type (Postgres: `varchar` via `JsonFormatMapper`, NOT `jsonb`) and you lose the GIN-indexable, operator-queryable advantages that motivated the `jsonb` choice. Don't write tests that assume H2 compatibility for these entities — `@DataJpaTest` with the default H2 replaces the `jsonb` column with `clob` and query semantics break silently. Use `@SpringBootTest` + Testcontainers Postgres (the existing pattern) or the `@AutoConfigureDataJpaTest` override that keeps the project's Postgres datasource. If a future hypothetical requires a non-Postgres database, the column has to be split into typed fields or migrated to `TEXT` with application-layer JSON parse-on-read — both ugly, both tracked in DEFERRED_WORK if it becomes real.

### F.56 `isApiError(e)` and `instanceof ApiError` are both needed — MSW + Vite HMR can produce cross-realm error objects

**Why:** the `ApiError` class check `e instanceof ApiError` is the natural first-line detection in a mutation's `onError` handler. In production this is enough. In the Vitest + MSW + Vite setup, however, HMR-style reloads and the React Query test wrapper can produce an error object whose prototype is a *structurally identical but different* `ApiError` class from a prior bundle — `e instanceof ApiError` returns `false` even though the shape is right. The `isApiError` duck-type helper (`typeof e.problem === "object" && typeof e.problem.status === "number"`) catches this cross-realm case.

Discovered during sub-spec 2 Task 9 cancel-modal tests when the error-branch assertion failed intermittently — `instanceof` missed the cross-realm instance and the handler fell through to the generic fallback message. The canonical pattern — `if (e instanceof ApiError || isApiError(e)) { /* use e.problem */ }` — covers both.

**How to apply:** in every mutation `onError` that wants to read `error.problem`, use the OR pattern: `e instanceof ApiError || isApiError(e)`. `CancelListingModal`, `useActivateAuction`, and the listing-wizard forms are the reference callers. Adding `instanceof` alone is the common mistake — the test will pass 9 times out of 10 and fail once on a suspicious rerun.

### F.57 `listMyAuctions` status query param is ignored by the backend today — filter client-side

**Why:** the sub-spec 2 Task 10 My Listings tab needs a bucketed filter (Active / Drafts / Ended / Cancelled / Suspended — see `FILTER_GROUPS` in `lib/listing/auctionStatus.ts`). The natural implementation is to pass `status=ACTIVE` or `status=DRAFT,DRAFT_PAID,VERIFICATION_PENDING,VERIFICATION_FAILED` on `GET /api/v1/users/me/auctions` and let the backend do the heavy lifting. As of sub-spec 2, the backend's `AuctionController.listMine` ignores query params entirely — it returns every auction the seller owns regardless of filter (see `AuctionService.loadOwnedBy`). The frontend `listMyAuctions(params)` accepts the params for forward compatibility but they're dropped in flight. Shipping "now" with a client-side filter is correct — Phase 1 sellers have at most a few dozen listings — but a future implementer reading just the frontend API client might assume the param works and wire UI that silently falls through.

**How to apply:** the My Listings tab filters client-side via `useMyListings.applyFilter` (see `hooks/useMyListings.ts`). When the backend grows a real filter (likely when listing volume per seller crosses ~100 and the all-listings payload gets expensive), update `AuctionController.listMine` to accept a `status` multi-value param, update `AuctionService.loadOwnedByFiltered`, and drop `applyFilter` from the hook. Do NOT do both — either the backend filters or the frontend filters, not both, or you'll have to keep the two bucket mappings in sync forever. The canonical status-bucket definition is `FILTER_GROUPS` in `frontend/src/lib/listing/auctionStatus.ts`; mirror it server-side when the backend starts honoring the param.

### F.58 Pessimistic lock on the auction row requires an active transaction to actually lock

**Why:** `AuctionRepository.findByIdForUpdate` is annotated `@Lock(LockModeType.PESSIMISTIC_WRITE)` — it issues a `SELECT ... FOR UPDATE` and blocks concurrent writers on the same row. But Hibernate only honors the lock when the query runs inside an active Spring-managed transaction. Calling `findByIdForUpdate` from a non-`@Transactional` method (or from a `@Transactional(propagation = NOT_SUPPORTED)` boundary) issues the SQL but acquires no row lock — concurrent bid/cancel/suspend paths will race silently. This is the class of bug that passes every unit test (the repo call succeeds, data comes back) and fails only under real concurrent load.

**How to apply:** every state-mutating path on an auction (`BidService.placeBid`, `ProxyBidService.create/update/cancel`, `AuctionEndTask.closeOne`, `CancellationService.cancel`, `OwnershipCheckTask.checkOne`) MUST enter through a `@Transactional` boundary before calling `findByIdForUpdate`. When adding a new mutator, the checklist is: (1) the entry method has `@Transactional`; (2) the first repo call inside it is `findByIdForUpdate`; (3) no other repo calls on the auction row bypass the locked instance. The concurrency-regression pins (`BidBidRaceTest`, `BidCancelRaceTest`, `BidSuspendRaceTest`, `BidSchedulerRaceTest`) exist specifically to catch regressions where a new call site forgets the transaction — a new mutator without a race test will pass today and manifest as a Phase 1 production anomaly months later.

### F.59 Spring `@Transactional` self-invocation bypasses the proxy — schedulers MUST dispatch to a separate bean

**Why:** Spring's `@Transactional` is enforced by an AOP proxy — the annotation only takes effect when the call crosses the proxy boundary. If `AuctionEndScheduler.sweep()` (a `@Scheduled` method) directly called a `@Transactional` helper on the same class, the call would bypass the proxy entirely and the "transactional" method would run with no transaction — meaning `findByIdForUpdate` acquires no lock (F.58), `afterCommit` synchronisations never fire, and any rollback is silent. This is the #1 gotcha when wiring scheduled transactional work.

**How to apply:** `AuctionEndScheduler.sweep()` (no transaction, just iterates) dispatches to `AuctionEndTask.closeOne(Long)` (`@Transactional`, lives in a separate `@Service` bean) for exactly this reason. When adding a new scheduler that dispatches transactional work, the worker MUST live in a separate bean and be autowired in — even if "it's only one method" and "it feels tidier to keep it in the scheduler class." Inline-helper-on-same-class is the bug; separate-bean is the fix. There is no `@Transactional(propagation = REQUIRES_NEW)` escape hatch that makes self-invocation work — the annotation processor runs at the proxy, and the proxy is never in the picture on a same-instance call.

### F.60 Snipe-extension evaluation must happen per emitted bid row, not per transaction

**Why:** A single `BidService.placeBid` call can emit TWO bid rows (a manual bid at L$500 against a competing proxy with `maxAmount=1000` emits both the manual row and a `PROXY_AUTO` counter at L$550). Spec §7 says snipe-extension is evaluated against each bid's timestamp — so if both bids land inside the snipe window, `endsAt` extends from the first bid's timestamp AND from the second bid's timestamp sequentially. Hoisting the snipe check outside the bid-emission loop (evaluating it once per transaction) would only apply one extension, silently dropping the second extension that the spec requires.

**How to apply:** `BidPlacementHelpers.applySnipeAndBuyNow(auction, emitted, clock, proxyBidRepo)` iterates `emitted` and re-evaluates the snipe window against `auction.endsAt` on EVERY iteration — the list of emitted bids is processed sequentially, and each iteration can mutate `auction.endsAt`. Do not refactor this to "compute the max timestamp and evaluate once" — the extensions stack. `BidServiceSnipeTest` + `BidVsProxyCounterIntegrationTest` pin the per-row semantics; if you need to add a new snipe branch, add a case to those suites and run them against real Postgres (not a mock that happens to accept the simpler form).

### F.61 `PROXY_AUTO` bid rows always persist `ip_address = null` — never inherit the upstream caller's IP

**Why:** When a manual bid triggers a proxy counter-bid, the naive implementation is to copy the manual bid's request metadata (including IP) onto the proxy's emitted row. This is wrong on two levels: (1) the proxy owner did not initiate this request, so logging their "IP address" as the manual bidder's IP misattributes network-layer evidence; (2) anti-fraud correlation (Epic 10) clusters by IP + userId — contaminating the proxy owner's IP history with manual bidders' IPs flags legitimate users as coordinating with strangers. The bug is invisible until Epic 10 starts triaging fraud signals and the signals are all wrong.

**How to apply:** every `BidType.PROXY_AUTO` row is persisted with `ipAddress=null`. The helper methods that insert proxy counter-bids (`BidService.insertProxyAutoBid`, `ProxyBidService.resolveProxyResolution` when it emits two rows) must pass `null` for the IP argument regardless of what the upstream HTTP request carried. Do not "plumb through the IP for completeness" — the null is load-bearing. Code review rule: any `new Bid(...)` with `type = PROXY_AUTO` and a non-null `ipAddress` is a bug.

### F.62 WebSocket publish must fire from `TransactionSynchronization.afterCommit` — never inline during the transaction

**Why:** If `BidService.placeBid` called `broadcastPublisher.publishSettlement(...)` inline, subscribers on `/topic/auction/{id}` could receive an envelope containing a `currentBid` that the transaction has not yet committed — and in the worst case, has not yet rolled back. Two failure modes: (1) the frontend displays a `currentBid` that vanishes on next page load because the transaction rolled back; (2) a subscribing client's subsequent `GET /api/v1/auctions/{id}` returns a stale value because replica lag makes the read-after-broadcast-but-before-commit ordering possible. Both are silent data-integrity bugs that are untestable without real concurrency.

**How to apply:** every `broadcastPublisher.publish*` call goes through `TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() { @Override public void afterCommit() { publisher.publishX(envelope); } })`. `BidService`, `ProxyBidService`, `AuctionEndTask`, `CancellationService`, and `OwnershipCheckTask` all follow this pattern. Consequences for correctness: (a) if the transaction rolls back, no publish happens (correct — there is no state to broadcast); (b) the subscriber observes the envelope after the DB has committed, so a follow-up REST read is guaranteed to see at least the broadcast state. Do not bypass the synchronization to "make the test easier" — tests that need to observe the envelope should use `CapturingAuctionBroadcastPublisher` + `@DirtiesContext` to hold a real transaction open.

### F.63 Auction-end scheduler MUST re-acquire the lock AND re-check conditions inside `closeOne`

**Why:** `AuctionRepository.findActiveIdsDueForEnd(now)` is a lock-free read that returns candidate IDs for closing. Between the scheduler's query and `AuctionEndTask.closeOne(id)` opening its transaction, two things can happen: (1) a bid on that auction extends `endsAt` via snipe-protection, pushing it past `now` — closing now would terminate a legitimately-active auction mid-bid; (2) a concurrent `CancellationService.cancel` flips status to `CANCELLED` — closing now would overwrite a user-driven cancel with a scheduler-driven close. Both are silent correctness bugs.

**How to apply:** `AuctionEndTask.closeOne(Long)` opens with `findByIdForUpdate(id)` (F.58) and THEN re-checks three gates: (1) auction present, (2) `status == ACTIVE`, (3) `endsAt <= now(clock)`. All three must pass before closing. If any gate fails, the transaction commits a no-op and moves on — this is how the snipe-race is resolved deterministically. `AuctionEndTaskTest` pins all three skip paths; `BidSchedulerRaceTest` drives the full race at `@SpringBootTest` scale. When adding a new scheduler-driven transition, the same pattern applies: acquire the lock, re-read state, re-verify every precondition the scheduler-query implied.

### F.64 Partial unique indexes are not expressible via JPA `@Index` — use a startup initializer

**Why:** `proxy_bids` needs `(auction_id, user_id) UNIQUE WHERE status = 'ACTIVE'` — there can be at most one ACTIVE proxy per (auction, bidder) pair, but EXHAUSTED / CANCELLED history rows must be allowed to accumulate (resurrection flips one back to ACTIVE; old ones stay for audit). JPA's `@Index` syntax supports only column lists — no `WHERE` clause — so there is no `@Table(indexes = ...)` annotation that produces this constraint. Letting Hibernate auto-generate an unconditional `UNIQUE (auction_id, user_id)` instead would reject every legitimate EXHAUSTED history row.

**How to apply:** `ProxyBidActiveIndexInitializer` runs on `ApplicationReadyEvent` and executes `CREATE UNIQUE INDEX IF NOT EXISTS uq_proxy_bids_active_per_bidder ON proxy_bids (auction_id, user_id) WHERE status = 'ACTIVE'`. The `IF NOT EXISTS` clause makes it idempotent across restarts. The same initializer pattern handles `uq_auctions_parcel_locked_status` (Epic 03 sub-spec 1) and the `auctions_status_check` constraint refresh (F.51). When adding a new partial index or a CHECK constraint that depends on a runtime enum value, follow the initializer pattern — do not try to coerce JPA annotations into expressing it. Do not write a Flyway migration either: F.43 established that entities are the schema source of truth for Phase 1.

### F.65 STOMP `/topic/auction/{id}` allowlist uses a full regex match, not a prefix match

**Why:** `JwtChannelInterceptor` allows anonymous `SUBSCRIBE` to `/topic/auction/{id}` (spec §4: auction detail pages are public). The naive implementation is `destination.startsWith("/topic/auction/")` — this is wrong. Prefix matching allows `/topic/auction/../admin/wiretap` or any broker-normalized path that happens to share the prefix on a broker swap that does not canonicalize. Even on SimpleBrokerMessageHandler today (which does not normalize), a future migration to RabbitMQ or ActiveMQ could quietly change that posture. A regex that pins the full shape closes the ambiguity.

**How to apply:** the allowlist is `Pattern.compile("^/topic/auction/\\d+$")` — anchored start-to-end, digits only for the auction id. Any new public destination (e.g., `/topic/market-stats`, `/topic/region/{regionName}`) must add an equivalent full-regex pattern — not a prefix check. `JwtChannelInterceptorTest` covers the allowlist matrix; when adding a new public destination, add a positive case for the allowed shape AND a negative case for an attempted bypass (`/topic/auction/1/../other`, `/topic/auction/1%2Fother`, etc.). Authenticated destinations (principal-gated) do not need the regex — they go through the default gate.

### F.66 Postgres TIMESTAMPTZ stores microseconds, not nanoseconds — test comparisons must tolerate rounding

**Why:** Java `OffsetDateTime` carries nanosecond precision. Postgres `TIMESTAMPTZ` stores microseconds — the last three digits of the nanosecond field are discarded on write, and the exact rounding behavior (truncate vs half-up) depends on the JDBC driver version and connection settings. A test that stamps `auction.endsAt = OffsetDateTime.now(clock)`, persists the auction, re-loads it, and asserts `reloaded.endsAt.isEqualTo(original)` will pass on one driver and fail on another — the flake is driver-dependent and survives CI reruns.

**How to apply:** when a test compares an in-memory `OffsetDateTime` to a DB-reloaded `OffsetDateTime`, use AssertJ's `isCloseTo(original, within(1, ChronoUnit.MICROS))` instead of `isEqualTo(original)`. The same applies to `Instant` comparisons through the same column type. If the production code needs microsecond equality (e.g., "did this bid land in the snipe window"), normalize both sides to microseconds before comparing (`.truncatedTo(ChronoUnit.MICROS)`) rather than assuming nanosecond equality. Do not "fix" a flaky test by retrying — the flake is a real correctness bug that a retry hides.

### F.67 Integration tests that need real commits must clean up explicitly — no transactional rollback safety net

**Why:** The default Spring Boot test style (`@Transactional` at the class level) rolls the transaction back after each test, so stored rows never leak across test methods. Epic 04 sub-spec 1 has multiple suites that CANNOT use this pattern because they rely on real commits: `BidWebSocketIntegrationTest` (the STOMP subscriber receives the envelope only after `afterCommit` fires, which needs a real commit); `BidBidRaceTest` / `BidCancelRaceTest` / `BidSuspendRaceTest` / `BidSchedulerRaceTest` (two transactions must actually conflict on the DB, which needs real commits on both threads); `AuctionEndIntegrationTest` (scheduler-driven closes depend on real state). These tests run with no class-level `@Transactional` — which means rows persist across methods unless the test cleans up.

**How to apply:** integration tests that need real commits MUST: (1) use `@DirtiesContext(classMode = AFTER_CLASS)` so Hikari pools reset between suites and Postgres's 100-connection ceiling is not exhausted; (2) have an explicit `@AfterEach` that deletes every row the test may have created — prefer JPA (`auctionRepo.deleteAll()`, `bidRepo.deleteAll()`, etc.) when the dependency order is obvious, or raw JDBC (`jdbcTemplate.execute("TRUNCATE bids, proxy_bids, auctions, parcels, users CASCADE")`) when it is not. Do not rely on "the next test overwrites it" — inserts are additive, not overwrite-by-default, and unique constraints will trip. When a new real-commit test is added and other tests in the suite start failing mysteriously, the first suspect is always incomplete cleanup in the new test's `@AfterEach`.

### F.68 WS re-attach registry: iterate live Map, not a snapshot

The `lib/ws/client.ts` `onConnect` sweep iterates `entries.values()` directly, NOT `Array.from(entries.values())`. This is load-bearing: if an `onMessage` callback synchronously calls `subscribe()` for a new topic during the sweep, the new entry gets attached in the same pass. Map iteration is spec-defined to visit entries added during iteration if not yet reached; snapshotting would require a second reconnect cycle to pick up the late entry.

**Trap to avoid:** a well-meaning refactor that swaps `for (const entry of entries.values())` → `for (const entry of Array.from(entries.values()))` "for safety" will silently reintroduce a timing race. The regression test at `client.test.ts::subscribeDuringSweep_lateAddedEntryAttachedInSamePass` pins this invariant — if that test breaks, DO NOT work around it by reordering code; understand what the snapshot lost.

### F.69 Auction detail mobile/desktop toggle: render both, let CSS pick — no `useMediaQuery`

**Why:** The `/auction/[id]` page renders BOTH the desktop sidebar `BidPanel` (wrapped `hidden lg:block`) AND the mobile `StickyBidBar` + `BidSheet` (wrapped `lg:hidden`) in the DOM. A naive "pick one based on `useMediaQuery('(min-width: 1024px)')`" approach would cause a hydration flash on mobile: the server has no viewport, renders the desktop sidebar, and the client swaps to the mobile bar after hydration. The flash is visible, janky, and the first interaction can target the wrong element. CSS visibility toggle is instant, SSR-safe, and costs only a slightly larger DOM. Do not regress this to a media-query hook "for performance" — measure the DOM cost before accepting the flash.

**How to apply:** any component that needs a breakpoint-driven layout switch on a primary interactive surface (auction detail, checkout, anything a user will interact with in the first second) should render both variants and toggle via `hidden lg:block` / `lg:hidden`. Hooks like `useMediaQuery` are fine for peripheral decisions (log-level, analytics flag, a collapsed nav preference) where a post-hydration swap is invisible. The tradeoff is DOM size — if a variant pair is very heavy (e.g. two full-screen editors), consider `lazy()` / `dynamic()` loading for the inactive variant instead of hoisting to a hook.

### F.70 Buy-now overspend guard fires on both place-bid and proxy-bid forms

**Why:** Backend accepts `amount > buyNowPrice` on place-bid (clamps to buyNowPrice, ends the auction inline with `endOutcome=BOUGHT_NOW`) AND `maxAmount >= buyNowPrice` on proxy-bid (immediately triggers buy-now through proxy resolution). Both are legal backend states, but the user needs to understand what is about to happen before submitting — a naive frontend that only guards one form lets the other slip through with no confirmation, and the user pays full buy-now instead of the increment they intended.

**How to apply:** place-bid form fires `ConfirmBidDialog` when `amount > buyNowPrice` with copy explaining "your bid exceeds buy-now — you won't pay more than L$buyNowPrice". Proxy-bid form fires the same dialog when `maxAmount >= buyNowPrice` with copy explaining "this will trigger immediate buy-now at L$buyNowPrice". The dialog's dismiss state is session-scoped (dismissed = don't re-prompt on large-bid threshold within the same session) but the buy-now threshold is always confirmed — large-bid is an opinion, buy-now is a binding terminal action. Both forms MUST share the same `ConfirmBidDialog` component so the copy stays in lockstep. `BidForm.test.tsx` and `ProxyBidForm.test.tsx` each pin the guard; do not consolidate into one test that stubs out half the surface.

### F.71 Server-time offset corrects countdown for client clock drift

**Why:** `CountdownTimer` renders `remaining = endsAt - now`. If the user's clock is skewed (common on phones, Windows suspending the sync service, deliberate time-zone tricks), `now` is wrong and the countdown disagrees with the server's notion of "when does this auction actually end". The auction-end scheduler is driven by server time — a user whose client is 5 minutes ahead will see the timer hit zero five minutes before the auction actually ends, place a "last-second" bid that the server treats as normal, and be confused when snipe-extension doesn't fire the way they expected.

**How to apply:** `AuctionDetailClient` stores `serverTimeOffset = envelope.serverTime - clientNow()` in a module-level ref. `CountdownTimer` uses this offset: `remaining = endsAt - (clientNow() + offset)`. The first offset value comes from the server component's fetch response `Date` header (conservative fallback — may be off by a few seconds of network latency, but bounded). Every WS envelope carries `serverTime` and refines the offset on every message. Do not compute offset from a synthetic `/api/v1/server-time` endpoint — the existing WS envelope already carries the data; a separate endpoint is extra latency for no benefit.

### F.72 Merging WS envelope bids into React Query cache — dedupe by `bidId`

**Why:** Page 0 of the bid history list is kept fresh by merging `BID_SETTLEMENT.newBids[]` into the TanStack Query cache on every envelope. A reconnect race delivers the same bid through two paths: (a) the HTTP re-fetch on reconnect pulls the bid from the backend; (b) the in-flight WS envelope delivers it via `newBids`. Without dedup, the UI renders the same bid twice — visible artifacts in the history list and a double-count in the "X bidders" badge.

**How to apply:** the merge function builds a `Set<bidId>` from the existing cache entries, filters `newBids` to drop anything already present, then concatenates. `totalElements` is incremented by `newBids.length` (post-dedup) — in the duplicate-replay case it's deliberately over-counted by zero because the filter ran; in a missed-envelope case where the REST fetch sees a bid the WS didn't, the next REST reconcile (either pagination or an invalidate) corrects any drift. Do not drop the `bidId` dedup "because it adds allocation" — the allocation is bounded (page size = 20), and the correctness win is load-bearing. `BidHistory.test.tsx::mergesNewBidsAndDedupes` pins the invariant.

### F.73 `PagedResponse<T>` over raw `Page<T>` for JSON-stable pagination

**Why:** Spring Data 3.3+ emits a warning — `"Serializing PageImpl instances as-is is not supported, meaning that there is no guarantee about the stability of the resulting JSON structure!"` — whenever a controller returns `Page<T>` directly. The JSON shape today happens to be `{content, totalElements, totalPages, number, size, pageable: {...}, sort: {...}, ...}` but Spring reserves the right to change it between minor versions. The frontend's `types/page.ts` interface expects a pinned flat shape; a shape drift breaks every paginated list silently (the fields vanish and the UI renders empty-state instead of the rows).

**How to apply:** every REST controller that returns paginated data MUST return `PagedResponse<T>` (at `backend/src/main/java/com/slparcelauctions/backend/common/PagedResponse.java`), NOT `Page<T>` directly. The record pins `{content, totalElements, totalPages, number, size}`. Controller pattern: `return PagedResponse.from(page.map(dtoMapper::toDto));`. See CONVENTIONS.md for the convention note. A reviewer who sees `ResponseEntity<Page<T>>` in a new controller should reject the PR and ask for `PagedResponse<T>` — this is non-negotiable for consistency with the 7+ existing paginated endpoints.

### F.74 Headless UI Dialog — no swipe-to-dismiss on `BidSheet` (by spec)

**Why:** The mobile `BidSheet` closes via backdrop click, Escape, or the close button only. There is NO gesture library, NO custom touch math, NO swipe-to-dismiss. The drag handle at the top of the sheet is purely decorative (`aria-hidden`) — it signals "this is a sheet" visually but does nothing. Epic 04 sub-spec 2 §13 explicitly excludes swipe-to-dismiss to keep the dependency surface thin and the keyboard/screen-reader story tight. Adding a gesture library (react-spring, framer-motion, or a hand-rolled touch handler) pulls in a non-trivial bundle, introduces test-flakiness in JSDOM (touch events don't round-trip), and opens questions the spec does not answer (velocity threshold? rubber-banding? cancel region?).

**How to apply:** if swipe-to-dismiss is ever demanded, it's a deliberate scope addition with its own spec — not "a small UX polish". Draft the behavior matrix (threshold, cancel region, keyboard parity, accessibility announcement on dismiss) before touching the code. Until then, keep the drag handle `aria-hidden` and do not wire an `onTouchStart` to the sheet root. `BidSheet.test.tsx` asserts only the three supported close paths; do not add a "swipe dismiss" test that passes via direct handler invocation — that's a false signal.

### F.75 Pool-not-sticky terminal model — any terminal can execute any command

**Why:** All L$ held at the SLParcels avatar account level, not per-terminal. Any registered terminal can execute any command (Task 4's registration pool + Task 7's `findAnyLive` selection in `TerminalCommandDispatcherTask`). This is by design — it keeps the queue simple, avoids region-affinity hotspots, and lets terminals fail without stranding L$ that was "paid to them." The flipside is that the terminal that received a listing-fee payment is almost never the one that executes the refund; the terminal that received escrow funding is almost never the one that executes the payout.

**How to apply:** when debugging a commands-vs-terminals mismatch, don't chase "which terminal did this command originate from." The answer is "whichever one the dispatcher picked at the moment of `findAnyLive`." `TerminalCommand.terminalId` is stamped at dispatch time, not at queue time — the queued row has `terminalId=null`. If you ever feel the need to add "sticky" routing (same terminal that received payment executes refund), re-read spec §7.4 first; the current model is not an oversight.

### F.76 Atomic `ESCROW_PENDING → FUNDED → TRANSFER_PENDING` — the intermediate state is never externally observable

**Why:** `EscrowService.acceptPayment` walks the state machine `ESCROW_PENDING → FUNDED → TRANSFER_PENDING` inside a single transaction with both transitions validated against `ALLOWED_TRANSITIONS`. The row is saved in `TRANSFER_PENDING`; the `ESCROW_FUNDED` envelope's `state` field is already `TRANSFER_PENDING` when subscribers see it. The `FUNDED` state exists in the enum for state-machine auditability (so you can write down which transitions are legal in exactly one place) but there is no persistable FUNDED row and no `/topic` envelope showing `state=FUNDED`.

**How to apply:** if you are writing UI or admin tooling that needs to display the escrow in a "funded, awaiting transfer" state, use `state=TRANSFER_PENDING && fundedAt != null && transferConfirmedAt == null` as the predicate. Do not add a "funded" filter to the dashboard that looks for `state=FUNDED` — there will be zero rows. Do not add an envelope subscriber that listens for `state=FUNDED` — the type discriminator is `ESCROW_FUNDED` but the state value is never FUNDED.

### F.77 `TRANSFER_PENDING → COMPLETED` only flips on the payout callback — not on ownership confirmation

**Why:** `EscrowOwnershipCheckTask` confirming the seller has transferred the parcel does NOT flip the escrow to COMPLETED. It stamps `transferConfirmedAt` and calls `TerminalCommandService.queuePayout(escrow)`; the dispatcher POSTs to the terminal, the terminal executes `llTransferLindenDollars` to the seller's avatar, and only the payout-result callback (`POST /api/v1/sl/escrow/payout-result`) flips the state to COMPLETED. There is a window — "transfer confirmed, payout mid-flight" — where `state=TRANSFER_PENDING && transferConfirmedAt != null` is the normal steady state, not a bug.

**How to apply:** if you see an escrow row with `transferConfirmedAt != null && state=TRANSFER_PENDING`, don't mark it anomalous. Check `TerminalCommand` rows: if there's a PAYOUT command in QUEUED / IN_FLIGHT / FAILED-retrying state, the payout is still trying. Only when the PAYOUT is COMPLETED does the escrow flip to COMPLETED; only when the PAYOUT exhausts retries does it stall with `requires_manual_review=true` + an `ESCROW_PAYOUT_STALLED` envelope. The `EscrowStatusResponse.timeline` surfaces this naturally: "Transfer confirmed" appears at `transferConfirmedAt`, then the `LEDGER_AUCTION_ESCROW_PAYOUT` row appears at payout completion. A transfer-confirmed-but-not-completed row viewed before the payout lands shows the confirmed step and no ledger row yet.

### F.78 Payout-in-flight guard on the transfer-timeout sweep — do not drop the `NOT EXISTS` clause

**Why:** `EscrowRepository.findExpiredTransferPendingIds` filters out any escrow that has an active PAYOUT command (`status IN ('QUEUED','IN_FLIGHT','FAILED')` — the last because a FAILED command awaiting retry is still "in flight" for this purpose). Without the filter, an escrow whose ownership was confirmed and whose payout is mid-retry (transient terminal outage) would race with the 72h transfer-deadline sweep: the sweep would queue a REFUND while the PAYOUT is still attempting to deliver. Both eventually succeed and the escrow account double-spends.

**How to apply:** do NOT refactor the query to "simplify" by dropping the `NOT EXISTS` subquery. Do NOT re-implement the sweep as "find expired, then filter in Java" — the filtering must happen in the SQL WHERE clause so a concurrent `TerminalCommand` INSERT between `findAll()` and `filter()` doesn't slip through. `EscrowTimeoutTask.expireTransfer` additionally re-checks `countActivePayoutCommands` under the per-escrow pessimistic lock for the fine-grained race (see F.78 cross-ref with the timeout integration test). If you change the filter, the regression pin is `EscrowTimeoutIntegrationTest.transferTimeout_skipsEscrowsWithActivePayoutCommand`.

### F.79 Clock injection discipline — new escrow code uses injected `Clock`, Epic 03/04 code does not

**Why:** Every new service, scheduler, and task added in Epic 05 sub-spec 1 injects `Clock` via the `ClockConfig` bean and calls `OffsetDateTime.now(clock)` instead of `OffsetDateTime.now()`. This lets deadline tests advance a `MutableFixedClock` and assert that the code hits the expected branch. Existing Epic 03 / Epic 04 services use raw `.now()` — they're unaffected at runtime (both resolve to `Clock.systemDefaultZone()`), but they can't be cleanly tested with a frozen clock.

**How to apply:** if you add a test that fast-forwards the clock and the assertion unexpectedly passes under wall-clock timing (or fails non-deterministically), the code path you're exercising probably hits a raw `.now()` somewhere. Grep for `OffsetDateTime\.now\(\)` (no argument) in the touched service — that's the bug. The fix is usually to inject `Clock` and swap the call. The "retrofit" entry in DEFERRED_WORK.md tracks the opportunistic cleanup pass.

### F.80 Enum CHECK constraint refresh — no manual DDL needed, but the initializer must stay on the classpath

**Why:** Adding an enum value to `FraudFlagReason`, `AuctionStatus`, `EscrowState`, etc. requires no migration. `EnumCheckConstraintSync` (a shared common helper) runs on `ApplicationReadyEvent` for each registered enum-column pair and rewrites the Postgres `CHECK` constraint to match the enum's current values. This is how new `FraudFlagReason` entries (`ESCROW_WRONG_PAYER`, `ESCROW_UNKNOWN_OWNER`, etc.) landed in Epic 05 sub-spec 1 without a Flyway migration. If you ever see a `DataIntegrityViolationException` containing `violates check constraint "<table>_<column>_check"` on a fresh enum value, the initializer component is either missing, disabled, or didn't register the enum/column pair.

**How to apply:** never write manual DDL to update an enum check constraint. If adding a new enum value, do nothing special — the initializer handles it on next boot. If the constraint rejects a new value in a test, verify the `@Component` is still on the classpath, check the startup logs for the `"Refreshed <name>_check CHECK constraint to N values"` line, and confirm the enum / column pair is registered in `EnumCheckConstraintInitializer` / `EscrowEnumCheckConstraintInitializer` / equivalent. Do NOT mark the schema with `ddl-auto: create` to "reset" — that wipes all data and still doesn't help if the initializer isn't running.

### F.81 Consecutive World API failure threshold — conservative default of 5, reset on any success

**Why:** `Escrow.consecutiveWorldApiFailures` counts the number of World API calls for an ownership check that returned a transient failure (5xx, timeout, connection refused) in a row. Default threshold is 5 (`slpa.escrow.ownership-api-failure-threshold`). At 5 consecutive failures, `EscrowOwnershipCheckTask` freezes the escrow with `FreezeReason=WORLD_API_PERSISTENT_FAILURE`. Any successful check resets the counter to 0 via `EscrowService.confirmTransfer` / `stampChecked` — meaning the threshold means "5 failures in an unbroken run" not "5 failures ever."

**How to apply:** the paranoid default is deliberate — a seller who is actively defrauding SLParcels could (via some plausibly complex path) disrupt World API lookups for their specific parcel; freezing on a small unbroken run protects the winner's L$ while an admin investigates. If you lower the threshold to 3, be prepared for false-positive freezes under real-world Linden Lab API flakiness. If you raise it to 10, accept that an escrow with 9 consecutive failures and an attacker who flips the parcel owner to a third party on the 10th check window squeaks through. Changing this value is not a drive-by tweak — reason about the worst case first.

### F.82 Escrow WS envelopes are invalidation-only

The 9 `ESCROW_*` envelope types on `/topic/auction/{id}` are coarse cache-invalidation signals per spec §7.2. DO NOT add envelope-to-DTO merge logic on the frontend. Per-variant refinements (paymentDeadline on CREATED, reason on DISPUTED, etc.) are there for Epic 09 notifications consumers — the escrow page refetches the full DTO via GET after any envelope and that's the canonical source of truth. Merging 9 different envelope shapes into a cached response with a computed timeline array defeats the backend's "coarse" design intent for zero UX win.

### F.83 EscrowChip needs transferConfirmedAt to render correctly

`EscrowChip` has a state→label map that sub-splits `TRANSFER_PENDING` based on whether `transferConfirmedAt` is set. Callers with only the `state` can omit it and fall back to a generic label, but the escrow page, dashboard rows, and AuctionEndedPanel all have access to the full field via their DTO enrichment — they MUST pass it. If you see "Transfer pending" on the chip when you expected "Awaiting transfer" or "Payout pending", you forgot to thread `transferConfirmedAt`.

### F.84 EXPIRED state branches on fundedAt in the escrow page only

The backend sends a single `EXPIRED` state that covers two semantically distinct scenarios: winner-never-paid (payment timeout) and seller-never-transferred (transfer timeout, refund queued). The escrow page's `ExpiredStateCard` branches on `fundedAt` to choose copy (`fundedAt == null` → payment timeout, `fundedAt != null` → transfer timeout, refund). The banner on the auction detail page does NOT branch — both EXPIRED sub-states just show "Escrow expired" there because the banner's one-liner can't carry both nuances. If you're tempted to add the branching to the banner, the full detail lives on the escrow page — send users there instead of overloading the banner.

### F.85 inferEndOutcome / inferOutcomeFromDto helpers retired in sub-spec 2

The defensive `endOutcome` fallback helpers that previously lived in `AuctionEndedPanel` + `ListingSummaryRow` are gone. Sub-spec 1 backend guarantees `endOutcome` is always projected on ENDED auctions. If you're tempted to bring the helpers back "just in case," don't — the backend is authoritative and a null `endOutcome` on ENDED should surface as a bug, not be silently papered over with heuristics.

### F.86 — SKIP LOCKED is not portable SQL

`SELECT ... FOR UPDATE SKIP LOCKED` is Postgres-specific syntax. Spring
Data JPQL has no equivalent; the clause must be passed via a native query
(`@Query(..., nativeQuery = true)`). Repository methods using SKIP LOCKED
therefore cannot be re-used against an in-memory H2 or Derby test DB —
integration tests that want the lock behavior must run against Testcontainers
Postgres (or the shared dev Postgres container).

**Touchpoint:** `BotTaskRepository.claimNext`. If the project ever adds
an H2-backed test profile, the claim query has to be stubbed or the tests
gated on `@ActiveProfiles("test")` (Postgres).

### F.87 — `@Modifying` bypasses `@UpdateTimestamp`

Bulk UPDATE queries annotated with `@Modifying` skip Hibernate's entity
lifecycle, so `@UpdateTimestamp`-annotated columns like `lastUpdatedAt`
are NOT refreshed. If a bulk query changes row state, it must also set
`lastUpdatedAt = :now` explicitly in the SET clause.

**Touchpoint:** `BotTaskRepository.cancelLiveByAuctionIdAndTypes` /
`cancelLiveByEscrowId`. Any future bulk update on an entity with
`@UpdateTimestamp` must follow the same pattern.

### F.88 — Hibernate `ddl-auto: update` does not widen CHECK constraints

When a Java enum gains a new value (e.g., `BotTaskType` adds `MONITOR_AUCTION`),
Hibernate's `update` mode does NOT rewrite the existing Postgres CHECK
constraint. Inserts with the new value fail at the DB level with
`check constraint violated` until the constraint is manually refreshed.

**Solution:** Register a per-(table, column, enum) `@Component` that
invokes `EnumCheckConstraintSync.sync(...)` on `ApplicationReadyEvent`.
See `BotTaskTypeCheckConstraintInitializer` /
`BotTaskStatusCheckConstraintInitializer` /
`FraudFlagReasonCheckConstraintInitializer`.

**Touchpoint:** every enum column in the DB. When adding a new
`@Enumerated(EnumType.STRING)` column, also add a constraint initializer
alongside the entity.

### F.89 — .NET 8 container default port is 8080

ASP.NET Core 8 defaults to listening on port `8080` inside containers via
`ASPNETCORE_HTTP_PORTS`. If the Dockerfile `EXPOSE`s a different port (e.g.,
`8081`), the app still listens on `8080` and every healthcheck hitting
the exposed port fails silently.

**Solution:** set `ENV ASPNETCORE_HTTP_PORTS=<port>` in the Dockerfile
BEFORE `ENTRYPOINT`. Or override via compose `environment:` block. Both
patterns work; Dockerfile is more robust.

**Touchpoint:** `bot/Dockerfile`. Any future .NET-in-container service
must set this env var explicitly.

### F.90 — Hibernate collection-fetch + pagination = in-memory pagination (HHH90003004)

Fetching a `@OneToMany` / `@ManyToMany` collection via `@EntityGraph` on
a paginated query triggers HHH90003004 — Hibernate can't paginate in
SQL, fetches all matching rows into memory, and paginates in Java. At a
few hundred active rows this is invisible; at a few thousand it's a
full-table scan into heap on every cache miss.

**Rule:** on paginated queries, only join-fetch `@ManyToOne`
associations. For collections, batch-load with a second query keyed by
the page's IDs (`WHERE parent_id IN (:pageIds)`).

**Single-row fetches** (like `GET /auctions/{id}`) are fine — no
pagination means the trap doesn't apply. Join-fetch collections there.

**Reference:** Epic 07 sub-spec 1 §6.3; mapper in
`AuctionSearchResultMapper.java`.

### F.91 — EXPLAIN ANALYZE in CI against Testcontainers / small fixtures

Postgres' planner chooses between seq scan and index scan based on
table statistics. A Testcontainers fixture with a few hundred rows
often fits in a single page, so the planner legitimately picks
`Seq Scan` — even though the index is present. Asserting on plan
shape in CI flakes.

**Rule:** CI tests assert on **index existence** via `pg_indexes`
(see `PgIndexExistenceTest`). Actual `EXPLAIN ANALYZE` plan-shape
verification happens manually against a staging-sized dataset ahead
of releases or during query tuning, not as a CI gate.

**Reference:** Epic 07 sub-spec 1 §13.

### F.92 — Spring user-destination paths: client subscribes to `/user/queue/X`, never `/user/{id}/queue/X`

The shorthand `/user/{id}/queue/*` shows up in design docs and ledger entries
but is **never** the literal subscription path. Spring's `UserDestinationResolver`
resolves the principal from the STOMP session (set by `JwtChannelInterceptor` on
CONNECT via `accessor.setUser(StompAuthenticationToken)`) and translates the
client's `/user/queue/X` subscription into a session-specific destination. The
backend publishes via `convertAndSendToUser(String.valueOf(userId), "/queue/X", ...)`.

A subscription path that includes a literal user id (e.g.,
`/user/123/queue/notifications`) is a security hole — any authenticated user
could subscribe to any other user's queue by guessing IDs. The `WebSocketConfig`
broker registration must include `/queue` as a destination prefix
(`enableSimpleBroker("/topic", "/queue")`), or `convertAndSendToUser` silently
drops the message because there's no broker for the destination.

### F.93 — Notification publish lifecycle differs by recipient cardinality

Single-recipient publishers (`outbid`, `escrowFunded`, etc.) run **in-tx** with
the originating event — atomic with the event, exceptions roll back the parent.
Acceptable: single recipient = small surface, real failures *should* roll back.

Fan-out publishers (only `listingCancelledBySellerFanout` today) run as
**afterCommit batch** with per-recipient try-catch + `TransactionTemplate`
configured `PROPAGATION_REQUIRES_NEW`. The cancellation is the business event;
notification delivery is the side effect — a side effect must never block the
primary action. afterCommit (rather than REQUIRES_NEW *inside* the parent tx)
also prevents orphan notifications when the parent rolls back unrelatedly.

Mixing these lifecycles produces either:
- "one bad bidder kills the cancellation" (fan-out in-tx)
- "orphan notifications when parent rolls back" (per-recipient REQUIRES_NEW
  inside parent tx)

If you add a new fan-out method, name it with a `Fanout` suffix and accept
`List<Long>` recipients — match `listingCancelledBySellerFanout`'s shape.

### F.94 — Coalesce uses Postgres ON CONFLICT, not find-then-insert-with-retry

The naive race-handling pattern (catch `DataIntegrityViolationException` →
retry as UPDATE) marks the parent transaction rollback-only on the exception,
killing the originating business event (e.g., a bid settlement). The native
`ON CONFLICT (user_id, coalesce_key) WHERE read = false DO UPDATE` upsert
avoids the exception path entirely.

Index design: partial unique on `(user_id, coalesce_key) WHERE read = false`,
created via `NotificationCoalesceIndexInitializer` because Hibernate's
`ddl-auto: update` cannot emit partial indexes. Null `coalesce_key` values
never conflict (NULL ≠ NULL semantics in Postgres unique constraints), so the
same UPSERT query handles both coalescing and non-coalescing categories — no
service-layer branching.

The `xmax = 0` vs `xmax = current_txid` trick in the `RETURNING` clause tells
the DAO whether the operation was insert or update without a second roundtrip.
This drives the `isUpdate` flag on the `NOTIFICATION_UPSERTED` WS envelope,
which the frontend uses to decide whether to prepend (insert) or replace-by-id
(update) in the dropdown cache.

### F.95 — `llInstantMessage` truncates at 1024 BYTES, not characters

The natural Java `String.length()` measures UTF-16 code units; SL's
`llInstantMessage` truncates the IM at 1024 **bytes** in UTF-8 encoding.
Multi-byte UTF-8 characters (CJK, emoji, accented Latin) push the byte count
above the char count. SL silently truncates from the end of the string — no
error, no warning, no return value — and the deeplink in SLParcels's IM template
lives at the end of the assembled message. A 1023-character string with
multi-byte content can occupy 1500+ bytes; the deeplink gets cleanly cut off.

`SlImMessageBuilder` measures `text.getBytes(StandardCharsets.UTF_8).length`
and ellipsizes the body, never the prefix or deeplink. Three mandatory test
cases (multi-byte parcel name, emoji parcel name, long-body forcing
truncation) verify the deeplink survives. Adding a new component to the
assembled message (e.g., a timestamp) requires updating the byte-budget
accounting in `SlImMessageBuilder` — the budget assumes exactly
`PREFIX + title + SEPARATOR + body + SEPARATOR + deeplink`.

### F.96 — Single-recipient publish path is afterCommit-then-REQUIRES_NEW; fan-out path is in-the-REQUIRES_NEW

The two notification dispatch sites have different reliability postures and
different transaction structures.

**Single-recipient path** (`NotificationService.publish`): in-app row commits
first as part of the parent transaction, then `afterCommit` runs
`slImChannelDispatcher.maybeQueue` which opens its own REQUIRES_NEW. If the
IM-queue write fails, the in-app row already committed, the parent business
event already committed, and the only loss is the IM. **In-app guaranteed,
IM best-effort.**

**Fan-out path** (`NotificationPublisherImpl.listingCancelledBySellerFanout`):
per-recipient REQUIRES_NEW lambda contains the in-app DAO upsert AND the IM
queue write as siblings. If the IM-queue write fails, that recipient's
in-app row also rolls back; the per-recipient try-catch isolates this from
sibling recipients. **In-app + IM atomic per recipient; sibling recipients
independent.**

Mixing these mental models produces either:
- "One bad bidder kills the cancellation" — if you put fan-out atomic with the parent transaction.
- "In-app row exists but IM never queued because the dispatcher hook failed silently" — if you inline the dispatcher into the single-recipient path's parent transaction.

Future contributors adding a new fan-out method must follow the existing
pattern: per-recipient REQUIRES_NEW lambda containing all per-recipient writes
as siblings, with a per-recipient try-catch wrapping the lambda. Name with a
`Fanout` suffix (sub-spec 1's convention).

### F.97 — Adding a new terminal status to `sl_im_message` requires updating the cleanup predicate

`SlImCleanupJob` stage 2 deletes rows via `WHERE status IN ('DELIVERED',
'EXPIRED', 'FAILED') AND updated_at < retention_cutoff`. The IN-list
enumerates terminal statuses. Adding a new one (e.g., a future
`RETRY_SCHEDULED` for a deferred retry primitive) without updating the IN-list
means those rows accumulate forever — defeating the very `SELECT status,
count(*) FROM sl_im_message GROUP BY status` query the rolling 30-day window
was supposed to keep meaningful. Add the new status to the predicate in the
same commit that introduces the status.

### F.98 — LSL `llInstantMessage` is fire-and-forget; `/failed` has no LSL caller

The `sl-im-dispatcher` script unconditionally calls
`POST /api/v1/internal/sl-im/{id}/delivered` after every `llInstantMessage`
because LSL provides no delivery signal — `llInstantMessage` returns `void`,
raises no event on failure, and produces no observable side effect when the
recipient UUID is invalid or offline-unreachable.

This means FAILED rows in `sl_im_message` only appear via:
1. Manual operator intervention — direct SQL UPDATE in production (rare;
   usually for support clearing a stuck row).
2. A future revision of `dispatcher.lsl` that pre-validates avatar UUIDs
   (e.g., `llRequestAgentData` against `DATA_ONLINE` before sending, or a
   `NULL_KEY` guard).

If support runs `SELECT status, count(*) FROM sl_im_message GROUP BY status`
and sees zero FAILED rows, that's correct behavior. Not a missing pipeline.

### F.99 — Admin bootstrap config WILL re-promote a deliberately-demoted bootstrap username

The `slpa.admin.bootstrap-usernames` list is a forward-promote-on-startup
mechanism, not a configurable opt-out. The `WHERE u.role = 'USER'` guard
catches deliberately-demoted bootstrap usernames on next restart and
re-promotes them. To permanently demote a bootstrap username, **remove it
from the config list** AND bump `tokenVersion` (else outstanding tokens
keep working until expiry). Matching is case-insensitive (the JPQL
lowercases both sides). Documented as intentional in spec
2026-04-26 §10.6.

### F.100 — Demoting an admin requires both `role = USER` AND `tokenVersion + 1`

`UPDATE users SET role = 'USER' WHERE id = ?` alone is insufficient.
Existing access tokens carry `role: "ADMIN"` in their JWT claim and stay
valid until expiry — a demoted admin keeps full access for up to one
access-token lifetime. The `tv` bump invalidates all outstanding
tokens. Either do both ops in one transaction (preferred) or bump tv as
the LAST step so the role flip is observable across the cluster before
tokens get invalidated.

### F.101 — JWT-claim authority mapping is the only source of `ROLE_*` authorities

`hasRole("ADMIN")` in SecurityConfig depends on JwtAuthenticationFilter
emitting `ROLE_ADMIN` authority. The filter reads `principal.role()` and
prefixes with `ROLE_`. If the filter's third constructor arg is changed
back to empty `List.of()` for any reason, ALL admin matchers silently
fail closed (every request 403s). Tests at `AdminAuthGateSliceTest`
verify the round-trip — they are the canary.

### F.102 — Stats endpoint is uncached and runs 10 queries per page load

`GET /api/v1/admin/stats` runs three `count(*)` against fraud_flags +
escrows, four `count(*)` against auctions/users/escrows + the
`countByStateNotIn` set check, and two `sum(*)` against escrows. Single
read-only transaction, no Redis cache. Acceptable today (admin traffic
is low). If pre-launch volume makes the dashboard noticeably slow, the
fix is a 30-second Redis cache, NOT N+1 fixes — there are no joins to
optimize.

### F.103 — Admin-cancel must NOT bump the seller's penalty ladder

`CancellationLog.cancelledByAdminId IS NULL` is the load-bearing predicate
in `countPriorOffensesWithBids`. Without it, every admin-removed listing
counts as a seller offense — a seller who's been wrongly reported but
exonerated would still climb the ladder. Test
`CancellationServiceCancelByAdminTest.priorOffensesQueryExcludesAdminCancel`
is the canary.

### F.104 — Cause-neutral fanout body string

`NotificationPublisher.listingCancelledBySellerFanout` body strings
("This auction has been cancelled. Your active proxy bid is no longer
in effect.") are deliberately cause-neutral so admin-cancel can call the
same method. If anyone reverts the body to seller-attributed copy ("The
seller cancelled..."), bidders on admin-cancelled auctions get a
misleading message. Existing seller-cancel tests assert against the
new copy — they're the canary.

### F.105 — Listing-level report actions touch ONLY OPEN reports

`AdminReportService.warnSeller / suspend / cancel` filter to OPEN status
when batching the report-state-change. DISMISSED reports stay DISMISSED
because each represents a deliberate per-report decision (with the
reporter's frivolous counter already incremented). Reclassifying them
on a listing-level action would undo that decision.

### F.106 — Ban cache TTL = 5 min; create/lift flushes immediately

`BanCheckService` caches both positive AND negative results with 5-min
TTL. `BanCacheInvalidator.invalidate(ip, uuid)` is called on ban-create
and ban-lift to clear the keys immediately. The 5-min cap limits the
worst-case stale window to 5 min — acceptable because admin actions are
infrequent and a banned user being one bid late to be blocked is a
non-event.

### F.107 — Listing-creation IP capture didn't exist before

`AuctionController.create` and `AuctionService.create` gained
`HttpServletRequest` / `String ipAddress` parameters in sub-spec 2. Any
test calling `auctionService.create(sellerId, req)` directly fails with
a method-not-found compile error — pass `null` or `""` as the new third
arg. The integration tests in this sub-spec already do this; older tests
that haven't been touched in a while may need a sweep.

### F.108 — Self-demote returns 409, NOT 403

A current admin trying to demote themselves gets `409 SELF_DEMOTE_FORBIDDEN`
from `AdminRoleService.demote`. The 409 is intentional — they have
permission to call the endpoint (they're an admin), but the operation
is forbidden by business rule. The frontend toast surfaces "You cannot
demote yourself."
