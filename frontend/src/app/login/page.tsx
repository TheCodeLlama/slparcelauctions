// frontend/src/app/login/page.tsx
import { Suspense } from "react";
import Link from "next/link";
import { AuthCard } from "@/components/auth/AuthCard";
import { LoginForm } from "@/components/auth/LoginForm";

export default function LoginPage() {
  return (
    <AuthCard>
      <AuthCard.Title>Welcome Back</AuthCard.Title>
      <AuthCard.Subtitle>Sign in to your SLParcels account.</AuthCard.Subtitle>
      <AuthCard.Body>
        <Suspense>
          <LoginForm />
        </Suspense>
        <div className="text-center">
          <Link
            href="/forgot-password"
            className="text-xs font-medium text-brand hover:underline"
          >
            Forgot your password?
          </Link>
        </div>
      </AuthCard.Body>
      <AuthCard.Footer>
        New to the curator?{" "}
        <Link href="/register" className="font-semibold text-brand hover:underline">
          Request Membership
        </Link>
      </AuthCard.Footer>
    </AuthCard>
  );
}
