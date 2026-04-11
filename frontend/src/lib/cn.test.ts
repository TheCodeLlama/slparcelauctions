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
