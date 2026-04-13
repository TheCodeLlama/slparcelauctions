// frontend/src/lib/auth/schemas.ts
import { z } from "zod";

/**
 * Email schema reused by register, login, and forgot-password forms.
 */
export const emailSchema = z
  .string()
  .min(1, "Email is required")
  .email("Enter a valid email")
  .max(255);

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
 * Register form schema. Composes email + passwordCreate + confirmPassword + terms.
 * Cross-field validation: passwords must match.
 */
export const registerSchema = z
  .object({
    email: emailSchema,
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
 * Login form schema. Email + non-empty password.
 */
export const loginSchema = z.object({
  email: emailSchema,
  password: passwordInputSchema,
});

/**
 * Forgot-password form schema. Email only.
 */
export const forgotPasswordSchema = z.object({
  email: emailSchema,
});

export type RegisterFormValues = z.infer<typeof registerSchema>;
export type LoginFormValues = z.infer<typeof loginSchema>;
export type ForgotPasswordFormValues = z.infer<typeof forgotPasswordSchema>;
