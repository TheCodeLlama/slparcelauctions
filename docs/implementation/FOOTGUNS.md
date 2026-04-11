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

**How to apply:**

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

This is non-negotiable. Without the meta-discipline of "every catch becomes a ledger entry," the ledger doesn't compound and we re-burn the same lessons.
