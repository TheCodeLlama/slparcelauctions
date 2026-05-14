// frontend/src/components/auth/LoginForm.tsx
"use client";

import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { useRouter, useSearchParams } from "next/navigation";
import { Input } from "@/components/ui/Input";
import { Button } from "@/components/ui/Button";
import { FormError } from "@/components/ui/FormError";
import { useLogin } from "@/lib/auth";
import { loginSchema, type LoginFormValues } from "@/lib/auth/schemas";
import { mapProblemDetailToForm } from "@/lib/auth/errors";
import { getSafeRedirect } from "@/lib/auth/redirects";

// Backend-facing field names used by the problem-detail mapper. The
// form-state field is `slpaLoginUsername` (uniquified to dodge browser
// autofill heuristics that match `name="username"` against unrelated
// saved emails from other origins). Login responses don't return
// VALIDATION_FAILED with field-level errors today -- AUTH_INVALID_CREDENTIALS
// surfaces as a form-level message -- so the mapper's per-field branch is
// effectively unused here, but the array stays in lockstep with the
// backend contract for future-proofing.
const KNOWN_FIELDS = ["username", "password"] as const;

export function LoginForm() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const login = useLogin();

  const form = useForm<LoginFormValues>({
    resolver: zodResolver(loginSchema),
    mode: "onBlur",
    reValidateMode: "onChange",
    defaultValues: { slpaLoginUsername: "", password: "" },
  });

  const onSubmit = form.handleSubmit((values) => {
    // Map the uniquified form field back to the backend's wire contract.
    login.mutate(
      { username: values.slpaLoginUsername, password: values.password },
      {
        onSuccess: () => {
          const next = getSafeRedirect(searchParams.get("next"));
          router.push(next);
        },
        onError: (error) => {
          mapProblemDetailToForm(error, form, KNOWN_FIELDS);
        },
      },
    );
  });

  const rootError = (form.formState.errors as { root?: { serverError?: { message?: string } } })
    .root?.serverError?.message;

  return (
    <form onSubmit={onSubmit} className="space-y-6" noValidate>
      <FormError message={rootError} />

      {/*
        The DOM input's `name` follows the RHF registered field name, so
        the schema rename to `slpaLoginUsername` propagates here without
        an inline override. See loginSchema's docstring for why a unique
        name kills the cross-origin email-autofill dropdown while
        autoComplete="username" still lets password managers fill the
        user's saved SLParcels credential via origin + role matching.
      */}
      <Input
        label="Username"
        type="text"
        autoComplete="username"
        {...form.register("slpaLoginUsername")}
        error={form.formState.errors.slpaLoginUsername?.message}
      />

      <Input
        label="Password"
        type="password"
        autoComplete="current-password"
        {...form.register("password")}
        error={form.formState.errors.password?.message}
      />

      <p className="text-[11px] font-medium text-fg-muted">
        Signed in for 7 days on this device
      </p>

      <Button
        type="submit"
        disabled={form.formState.isSubmitting || login.isPending}
        loading={login.isPending}
        fullWidth
      >
        Sign In
      </Button>
    </form>
  );
}
