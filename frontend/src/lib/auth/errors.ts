// frontend/src/lib/auth/errors.ts
import type { UseFormReturn, FieldValues, Path } from "react-hook-form";
import { isApiError } from "@/lib/api";
import type { ProblemDetail } from "@/lib/api";

/**
 * Maps a backend ProblemDetail error into react-hook-form field errors.
 *
 * Handles three paths:
 *   - VALIDATION_FAILED (400 with errors{} dict) → per-field form.setError
 *   - AUTH_USERNAME_EXISTS (409) → field-level on `username`
 *   - AUTH_INVALID_CREDENTIALS (401) → form-level root.serverError
 *   - Everything else → form-level root.serverError with the detail or generic
 *
 * Unknown-field guard: if a VALIDATION_FAILED entry references a field that
 * doesn't exist on the form, fall back to root.serverError with the
 * concatenated message and `console.warn` in dev. This is a drift-detection
 * mechanism — the form tells you "something new is being validated that you
 * don't know about" rather than failing silently.
 *
 * See spec §5 and FOOTGUNS §F.6, §F.7.
 */
export function mapProblemDetailToForm<T extends FieldValues>(
  error: unknown,
  form: UseFormReturn<T>,
  knownFields: readonly string[]
): void {
  if (!isApiError(error) || !error.problem) {
    form.setError("root.serverError" as Path<T>, {
      type: "server",
      message: "Something went wrong. Please try again.",
    });
    return;
  }

  const problem = error.problem as ProblemDetail;
  const code = problem.code as string | undefined;

  if (code === "VALIDATION_FAILED" && problem.errors && typeof problem.errors === "object") {
    const unknownFields: string[] = [];
    for (const [field, message] of Object.entries(problem.errors as Record<string, string>)) {
      if (knownFields.includes(field)) {
        form.setError(field as Path<T>, {
          type: "server",
          message,
        });
      } else {
        unknownFields.push(`${field}: ${message}`);
      }
    }
    if (unknownFields.length > 0) {
      if (process.env.NODE_ENV !== "production") {
        console.warn(
          "[mapProblemDetailToForm] Unknown fields in backend validation response:",
          unknownFields
        );
      }
      form.setError("root.serverError" as Path<T>, {
        type: "server",
        message: unknownFields.join("; "),
      });
    }
    return;
  }

  if (code === "AUTH_USERNAME_EXISTS") {
    form.setError("username" as Path<T>, {
      type: "server",
      message: "That username is already taken.",
    });
    return;
  }

  if (code === "AUTH_INVALID_CREDENTIALS") {
    form.setError("root.serverError" as Path<T>, {
      type: "server",
      message: "Username or password is incorrect.",
    });
    return;
  }

  // Fallback: generic form-level error.
  form.setError("root.serverError" as Path<T>, {
    type: "server",
    message: problem.detail ?? "Something went wrong. Please try again.",
  });
}
