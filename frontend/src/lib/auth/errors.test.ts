// frontend/src/lib/auth/errors.test.ts
import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { useForm } from "react-hook-form";
import { renderHook, act } from "@testing-library/react";
import { mapProblemDetailToForm } from "./errors";
import { ApiError } from "@/lib/api";

type TestForm = {
  email: string;
  password: string;
  confirmPassword: string;
  terms: boolean;
};

const KNOWN_FIELDS = ["email", "password", "confirmPassword", "terms"] as const;

function setupForm() {
  return renderHook(() => {
    const form = useForm<TestForm>({
      defaultValues: { email: "", password: "", confirmPassword: "", terms: false },
    });
    // Subscribe to formState.errors so the hook re-renders when errors change.
    void form.formState.errors;
    return form;
  });
}

describe("mapProblemDetailToForm", () => {
  let consoleSpy: ReturnType<typeof vi.spyOn>;

  beforeEach(() => {
    consoleSpy = vi.spyOn(console, "warn").mockImplementation(() => {});
  });

  afterEach(() => {
    consoleSpy.mockRestore();
  });

  it("maps VALIDATION_FAILED with errors{} to per-field setError calls", () => {
    const { result } = setupForm();
    // ApiError takes a single ProblemDetail argument.
    // ProblemDetail.errors is Record<string, string> (field -> message).
    const error = new ApiError({
      type: "https://slpa.example/problems/validation",
      status: 400,
      code: "VALIDATION_FAILED",
      errors: {
        email: "must be a valid email",
        password: "must be at least 10 characters",
      },
    });

    act(() => {
      mapProblemDetailToForm(error, result.current, KNOWN_FIELDS);
    });

    expect(result.current.formState.errors.email?.message).toBe("must be a valid email");
    expect(result.current.formState.errors.password?.message).toBe("must be at least 10 characters");
  });

  it("maps AUTH_EMAIL_EXISTS to a field-level error on email", () => {
    const { result } = setupForm();
    const error = new ApiError({
      type: "https://slpa.example/problems/auth/email-exists",
      status: 409,
      code: "AUTH_EMAIL_EXISTS",
    });

    act(() => {
      mapProblemDetailToForm(error, result.current, KNOWN_FIELDS);
    });

    expect(result.current.formState.errors.email?.message).toMatch(/already exists/i);
  });

  it("maps AUTH_INVALID_CREDENTIALS to root.serverError (NOT a field-level error)", () => {
    const { result } = setupForm();
    const error = new ApiError({
      type: "https://slpa.example/problems/auth/invalid-credentials",
      status: 401,
      code: "AUTH_INVALID_CREDENTIALS",
    });

    act(() => {
      mapProblemDetailToForm(error, result.current, KNOWN_FIELDS);
    });

    expect(result.current.formState.errors.email).toBeUndefined();
    expect(result.current.formState.errors.password).toBeUndefined();
    // root.serverError lives at errors.root?.serverError in RHF.
    const root = (result.current.formState.errors as any).root;
    expect(root?.serverError?.message).toMatch(/incorrect/i);
  });

  it("falls back to root.serverError for unknown fields and warns in dev", () => {
    const { result } = setupForm();
    const error = new ApiError({
      type: "https://slpa.example/problems/validation",
      status: 400,
      code: "VALIDATION_FAILED",
      errors: {
        unknownField: "some new validator",
      },
    });

    act(() => {
      mapProblemDetailToForm(error, result.current, KNOWN_FIELDS);
    });

    const root = (result.current.formState.errors as any).root;
    expect(root?.serverError?.message).toContain("unknownField");
    expect(consoleSpy).toHaveBeenCalled();
  });

  it("falls back to root.serverError for unknown error types", () => {
    const { result } = setupForm();
    const error = new ApiError({
      type: "https://slpa.example/problems/internal-server-error",
      status: 500,
      code: "INTERNAL_SERVER_ERROR",
      detail: "Something went wrong on our end.",
    });

    act(() => {
      mapProblemDetailToForm(error, result.current, KNOWN_FIELDS);
    });

    const root = (result.current.formState.errors as any).root;
    expect(root?.serverError?.message).toBe("Something went wrong on our end.");
  });

  it("falls back to a generic message when error is not an ApiError", () => {
    const { result } = setupForm();

    act(() => {
      mapProblemDetailToForm(new Error("network down"), result.current, KNOWN_FIELDS);
    });

    const root = (result.current.formState.errors as any).root;
    expect(root?.serverError?.message).toMatch(/something went wrong/i);
  });
});
