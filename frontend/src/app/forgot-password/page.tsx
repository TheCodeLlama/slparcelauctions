// frontend/src/app/forgot-password/page.tsx
"use client";

import Link from "next/link";
import { AuthCard } from "@/components/auth/AuthCard";
import { ForgotPasswordForm } from "@/components/auth/ForgotPasswordForm";

// STUB: ForgotPasswordForm renders a [STUB] indicator banner in its success
// state because no backend password-reset endpoint exists yet. See the four-
// step swap documented on `useForgotPassword` in lib/auth/hooks.ts.

export default function ForgotPasswordPage() {
  return (
    <AuthCard>
      <AuthCard.Title>Forgot Your Password?</AuthCard.Title>
      <AuthCard.Subtitle>
        Enter your email and we'll send you a reset link.
      </AuthCard.Subtitle>
      <AuthCard.Body>
        <ForgotPasswordForm />
      </AuthCard.Body>
      <AuthCard.Footer>
        Remember it after all?{" "}
        <Link href="/login" className="font-semibold text-primary hover:underline">
          Back to Sign In
        </Link>
      </AuthCard.Footer>
    </AuthCard>
  );
}
