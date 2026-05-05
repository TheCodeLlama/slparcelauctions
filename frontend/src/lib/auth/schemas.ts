// frontend/src/lib/auth/schemas.ts
import { z } from "zod";

/**
 * Email schema — only used by the orphan forgotPasswordSchema today, kept
 * for when email-based recovery returns. Do NOT use for login/register.
 */
export const emailSchema = z
  .string()
  .min(1, "Email is required")
  .email("Enter a valid email")
  .max(255);

/**
 * Username schema (login + register).
 *
 * Server is the source of truth — this regex is cosmetic UX. The server
 * accepts any printable Unicode (letters, marks, numbers, punctuation,
 * symbols, regular space) up to 64 codepoints after NFC + trim + whitespace
 * collapse. Frontend mirrors the regex and adds two cosmetic refinements
 * (no leading/trailing spaces, no double spaces) so the user is told
 * sooner.
 */
export const usernameSchema = z
  .string()
  .min(3, "At least 3 characters")
  .max(64, "At most 64 characters")
  .regex(/^[\p{L}\p{M}\p{N}\p{P}\p{S} ]+$/u, "Disallowed character")
  .refine((v) => v.trim() === v, "No leading or trailing spaces")
  .refine((v) => !/\s{2,}/.test(v), "Collapse multiple spaces to one");

/**
 * Password schema for CREATION (register form). Mirrors the backend regex
 * exactly:
 *   ^(?=.*[A-Za-z])(?=.*[\d\W]).{10,}$
 * 10+ chars, at least one letter, at least one digit OR symbol.
 */
export const passwordCreateSchema = z
  .string()
  .min(10, "At least 10 characters")
  .regex(
    /^(?=.*[A-Za-z])(?=.*[\d\W]).{10,}$/,
    "Must contain a letter and a digit or symbol"
  )
  .max(255);

/**
 * Password schema for INPUT (login form). Just non-empty.
 *
 * KEEP THIS DISTINCT FROM passwordCreateSchema. Login is checking credentials,
 * not creating a new password — a pre-existing user with a 6-character password
 * (from before regex tightening) must still be able to log in. A contributor
 * who "unifies" them breaks login for legacy passwords.
 */
export const passwordInputSchema = z
  .string()
  .min(1, "Password is required")
  .max(255);

/**
 * Register form schema. Composes username + passwordCreate + confirmPassword + terms.
 * Cross-field validation: passwords must match.
 */
export const registerSchema = z
  .object({
    username: usernameSchema,
    password: passwordCreateSchema,
    confirmPassword: z.string().min(1, "Confirm your password"),
    terms: z.literal(true, {
      errorMap: () => ({ message: "You must accept the terms" }),
    }),
  })
  .refine((data) => data.password === data.confirmPassword, {
    message: "Passwords don't match",
    path: ["confirmPassword"],
  });

/**
 * Login form schema. Username + non-empty password.
 */
export const loginSchema = z.object({
  username: usernameSchema,
  password: passwordInputSchema,
});

/**
 * Forgot-password form schema. Email only.
 *
 * ORPHAN: the page that mounts this is removed; component code stays for the
 * day email-based recovery returns. Do not delete.
 */
export const forgotPasswordSchema = z.object({
  email: emailSchema,
});

export type RegisterFormValues = z.infer<typeof registerSchema>;
export type LoginFormValues = z.infer<typeof loginSchema>;
export type ForgotPasswordFormValues = z.infer<typeof forgotPasswordSchema>;
