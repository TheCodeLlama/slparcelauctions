// frontend/src/app/login/page.tsx
"use client";

import Link from "next/link";
import { AuthCard } from "@/components/auth/AuthCard";
import { LoginForm } from "@/components/auth/LoginForm";

export default function LoginPage() {
  return (
    <AuthCard>
      <AuthCard.Title>Welcome Back</AuthCard.Title>
      <AuthCard.Subtitle>Sign in to your SLPA account.</AuthCard.Subtitle>
      <AuthCard.Body>
        <LoginForm />
        <div className="text-center">
          <Link
            href="/forgot-password"
            className="text-label-md text-primary hover:underline"
          >
            Forgot your password?
          </Link>
        </div>
      </AuthCard.Body>
      <AuthCard.Footer>
        New to the curator?{" "}
        <Link href="/register" className="font-semibold text-primary hover:underline">
          Request Membership
        </Link>
      </AuthCard.Footer>
    </AuthCard>
  );
}
