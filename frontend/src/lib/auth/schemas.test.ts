// frontend/src/lib/auth/schemas.test.ts
import { describe, it, expect } from "vitest";
import {
  emailSchema,
  passwordCreateSchema,
  passwordInputSchema,
  registerSchema,
  loginSchema,
  forgotPasswordSchema,
} from "./schemas";

describe("emailSchema", () => {
  it("accepts a valid email", () => {
    expect(emailSchema.safeParse("user@example.com").success).toBe(true);
  });

  it("rejects empty string", () => {
    expect(emailSchema.safeParse("").success).toBe(false);
  });

  it("rejects malformed email", () => {
    expect(emailSchema.safeParse("not-an-email").success).toBe(false);
  });
});

describe("passwordCreateSchema", () => {
  it("accepts a 10-char password with letter + digit", () => {
    expect(passwordCreateSchema.safeParse("hunter22ab").success).toBe(true);
  });

  it("rejects passwords shorter than 10 chars", () => {
    expect(passwordCreateSchema.safeParse("hunter1").success).toBe(false);
  });

  it("rejects passwords without a digit or symbol", () => {
    expect(passwordCreateSchema.safeParse("abcdefghijk").success).toBe(false);
  });

  it("accepts passwords with letter + symbol (no digit required)", () => {
    expect(passwordCreateSchema.safeParse("hunter!!ab").success).toBe(true);
  });
});

describe("passwordInputSchema", () => {
  it("accepts any non-empty password (login is checking credentials, not creating)", () => {
    expect(passwordInputSchema.safeParse("a").success).toBe(true);
    expect(passwordInputSchema.safeParse("legacy-short").success).toBe(true);
  });

  it("rejects empty password", () => {
    expect(passwordInputSchema.safeParse("").success).toBe(false);
  });
});

describe("registerSchema", () => {
  const valid = {
    email: "user@example.com",
    password: "hunter22ab",
    confirmPassword: "hunter22ab",
    terms: true as const,
  };

  it("accepts a valid register payload", () => {
    expect(registerSchema.safeParse(valid).success).toBe(true);
  });

  it("rejects when passwords don't match", () => {
    const result = registerSchema.safeParse({ ...valid, confirmPassword: "different" });
    expect(result.success).toBe(false);
    if (!result.success) {
      const messages = result.error.issues.map((i) => i.message);
      expect(messages).toContain("Passwords don't match");
    }
  });

  it("rejects when terms is not true", () => {
    const result = registerSchema.safeParse({ ...valid, terms: false });
    expect(result.success).toBe(false);
  });
});

describe("loginSchema", () => {
  it("accepts a valid login payload", () => {
    const result = loginSchema.safeParse({ email: "user@example.com", password: "anything" });
    expect(result.success).toBe(true);
  });

  it("rejects empty password", () => {
    const result = loginSchema.safeParse({ email: "user@example.com", password: "" });
    expect(result.success).toBe(false);
  });
});

describe("forgotPasswordSchema", () => {
  it("accepts an email-only payload", () => {
    expect(forgotPasswordSchema.safeParse({ email: "user@example.com" }).success).toBe(true);
  });

  it("rejects malformed email", () => {
    expect(forgotPasswordSchema.safeParse({ email: "not-an-email" }).success).toBe(false);
  });
});
