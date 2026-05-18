import { describe, expect, it } from "vitest";
import { humanizeEntryType } from "./humanizeEntryType";

describe("humanizeEntryType", () => {
  it("capitalises only the first word and lowercases the rest", () => {
    expect(humanizeEntryType("ADMIN_ADJUSTMENT")).toBe("Admin adjustment");
    expect(humanizeEntryType("SOME_FUTURE_TYPE_99")).toBe("Some future type 99");
    expect(humanizeEntryType("DEPOSIT")).toBe("Deposit");
  });

  it("collapses repeated and leading/trailing underscores", () => {
    expect(humanizeEntryType("__AGENT__FEE__CREDIT__")).toBe("Agent fee credit");
  });

  it("degrades empty / whitespace input to a generic label", () => {
    expect(humanizeEntryType("")).toBe("Transaction");
    expect(humanizeEntryType("   ")).toBe("Transaction");
    expect(humanizeEntryType("___")).toBe("Transaction");
  });
});
