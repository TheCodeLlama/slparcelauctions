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

const KNOWN_FIELDS = ["email", "password"] as const;

export function LoginForm() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const login = useLogin();

  const form = useForm<LoginFormValues>({
    resolver: zodResolver(loginSchema),
    mode: "onBlur",
    reValidateMode: "onChange",
    defaultValues: { email: "", password: "" },
  });

  const onSubmit = form.handleSubmit((values) => {
    login.mutate(values, {
      onSuccess: () => {
        const next = getSafeRedirect(searchParams.get("next"));
        router.push(next);
      },
      onError: (error) => {
        mapProblemDetailToForm(error, form, KNOWN_FIELDS);
      },
    });
  });

  const rootError = (form.formState.errors as { root?: { serverError?: { message?: string } } })
    .root?.serverError?.message;

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

      <Input
        label="Password"
        type="password"
        autoComplete="current-password"
        {...form.register("password")}
        error={form.formState.errors.password?.message}
      />

      <p className="text-label-sm text-on-surface-variant">
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
