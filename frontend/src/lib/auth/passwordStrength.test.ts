// frontend/src/lib/auth/passwordStrength.test.ts
import { describe, it, expect } from "vitest";
import {
  computePasswordStrength,
  strengthToBars,
  strengthToLabel,
} from "./passwordStrength";

describe("computePasswordStrength", () => {
  it("returns 'empty' for an empty string", () => {
    expect(computePasswordStrength("")).toBe("empty");
  });

  it("returns 'weak' for a short password with no class diversity", () => {
    expect(computePasswordStrength("abc")).toBe("weak");
  });

  it("returns 'fair' for a near-miss (8 chars with letter + digit)", () => {
    expect(computePasswordStrength("abcd1234")).toBe("fair");
  });

  it("returns 'good' for a password meeting the backend regex (10 chars + letter + digit)", () => {
    // Backend regex: ^(?=.*[A-Za-z])(?=.*[\d\W]).{10,}$
    expect(computePasswordStrength("hunter22ab")).toBe("good");
  });

  it("returns 'good' for a password meeting the backend regex with a symbol instead of digit", () => {
    expect(computePasswordStrength("hunter!!ab")).toBe("good");
  });

  it("returns 'strong' for a 14+ character password meeting the regex", () => {
    expect(computePasswordStrength("hunter22abcdef")).toBe("strong");
  });

  it("returns 'strong' for a 10-char password with 3+ character classes", () => {
    expect(computePasswordStrength("Hunter22!a")).toBe("strong");
  });

  it("never returns less than 'good' for any password satisfying the backend regex", () => {
    // Property-style check: take the simplest valid password and assert it's not weak/fair.
    const valid = "abcdefghi1";
    const result = computePasswordStrength(valid);
    expect(["good", "strong"]).toContain(result);
  });
});

describe("strengthToBars", () => {
  it("maps each strength level to its bar count", () => {
    expect(strengthToBars("empty")).toBe(0);
    expect(strengthToBars("weak")).toBe(1);
    expect(strengthToBars("fair")).toBe(2);
    expect(strengthToBars("good")).toBe(3);
    expect(strengthToBars("strong")).toBe(4);
  });
});

describe("strengthToLabel", () => {
  it("maps each strength level to its display label", () => {
    expect(strengthToLabel("empty")).toBe("");
    expect(strengthToLabel("weak")).toBe("Weak");
    expect(strengthToLabel("fair")).toBe("Fair");
    expect(strengthToLabel("good")).toBe("Good");
    expect(strengthToLabel("strong")).toBe("Strong");
  });
});
