// frontend/src/components/auth/ForgotPasswordForm.tsx
"use client";

import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { Input } from "@/components/ui/Input";
import { Button } from "@/components/ui/Button";
import { FormError } from "@/components/ui/FormError";
import { useForgotPassword } from "@/lib/auth";
import {
  forgotPasswordSchema,
  type ForgotPasswordFormValues,
} from "@/lib/auth/schemas";

// STUB: no backend password-reset endpoint exists yet. The success state below
// is UI-only — NO EMAIL IS ACTUALLY SENT. When the real endpoint ships, follow
// the four-step swap documented on `useForgotPassword` in lib/auth/hooks.ts.

export function ForgotPasswordForm() {
  const forgotPassword = useForgotPassword();

  const form = useForm<ForgotPasswordFormValues>({
    resolver: zodResolver(forgotPasswordSchema),
    mode: "onBlur",
    reValidateMode: "onChange",
    defaultValues: { email: "" },
  });

  const onSubmit = form.handleSubmit((values) => {
    forgotPassword.mutate(values.email);
  });

  const rootError = (form.formState.errors as { root?: { serverError?: { message?: string } } })
    .root?.serverError?.message;

  if (forgotPassword.isSuccess) {
    return (
      <div className="space-y-4 text-center">
        <div className="mb-4 rounded-md bg-info-bg px-3 py-2 text-[11px] font-medium text-info-flat">
          <strong>[STUB]</strong> Backend password-reset endpoint not yet
          implemented. No email will arrive. This success state is UI-only for
          the current task.
        </div>
        <h3 className="text-lg font-bold tracking-tight font-semibold text-fg">
          Check your email
        </h3>
        <p className="text-sm text-fg-muted">
          If an account exists, we&apos;ve sent a password reset link to your inbox.
        </p>
      </div>
    );
  }

  return (
    <form onSubmit={onSubmit} className="space-y-6" noValidate>
      <FormError message={rootError} />

      <Input
        label="Email"
        type="email"
        autoComplete="email"
        {...form.register("email")}
        error={form.formState.errors.email?.message}
      />

      <Button
        type="submit"
        disabled={form.formState.isSubmitting || forgotPassword.isPending}
        loading={forgotPassword.isPending}
        fullWidth
      >
        Send Reset Link
      </Button>
    </form>
  );
}
