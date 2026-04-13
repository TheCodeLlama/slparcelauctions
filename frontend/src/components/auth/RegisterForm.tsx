// frontend/src/components/auth/RegisterForm.tsx
"use client";

import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { useRouter, useSearchParams } from "next/navigation";
import Link from "next/link";
import { Input } from "@/components/ui/Input";
import { Button } from "@/components/ui/Button";
import { Checkbox } from "@/components/ui/Checkbox";
import { FormError } from "@/components/ui/FormError";
import { PasswordStrengthIndicator } from "@/components/ui/PasswordStrengthIndicator";
import { useRegister } from "@/lib/auth";
import { registerSchema, type RegisterFormValues } from "@/lib/auth/schemas";
import { mapProblemDetailToForm } from "@/lib/auth/errors";
import { getSafeRedirect } from "@/lib/auth/redirects";

const KNOWN_FIELDS = ["email", "password", "confirmPassword", "terms"] as const;

export function RegisterForm() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const register = useRegister();

  const form = useForm<RegisterFormValues>({
    resolver: zodResolver(registerSchema),
    mode: "onBlur",
    reValidateMode: "onChange",
    defaultValues: {
      email: "",
      password: "",
      confirmPassword: "",
      terms: false,
    },
  });

  // Watch the password field so the strength indicator updates live.
  const passwordValue = form.watch("password");

  const onSubmit = form.handleSubmit((values) => {
    register.mutate(
      {
        email: values.email,
        password: values.password,
        displayName: null,
      },
      {
        onSuccess: () => {
          const next = getSafeRedirect(searchParams.get("next"));
          router.push(next);
        },
        onError: (error) => {
          mapProblemDetailToForm(error, form, KNOWN_FIELDS);
        },
      }
    );
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

      <div>
        <Input
          label="Password"
          type="password"
          autoComplete="new-password"
          {...form.register("password")}
          error={form.formState.errors.password?.message}
        />
        <PasswordStrengthIndicator password={passwordValue ?? ""} />
      </div>

      <Input
        label="Confirm Password"
        type="password"
        autoComplete="new-password"
        {...form.register("confirmPassword")}
        error={form.formState.errors.confirmPassword?.message}
      />

      <Checkbox
        label={
          <>
            I agree to the{" "}
            <Link href="/terms" className="font-semibold text-primary hover:underline">
              Terms
            </Link>
          </>
        }
        {...form.register("terms")}
        error={form.formState.errors.terms?.message}
      />

      <Button
        type="submit"
        disabled={form.formState.isSubmitting || register.isPending}
        loading={register.isPending}
        fullWidth
      >
        Create Account
      </Button>
    </form>
  );
}
