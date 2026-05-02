// frontend/src/app/register/page.tsx
import { Suspense } from "react";
import Link from "next/link";
import { AuthCard } from "@/components/auth/AuthCard";
import { RegisterForm } from "@/components/auth/RegisterForm";

export default function RegisterPage() {
  return (
    <AuthCard>
      <AuthCard.Title>Create Your Account</AuthCard.Title>
      <AuthCard.Subtitle>Join the digital curator.</AuthCard.Subtitle>
      <AuthCard.Body>
        <Suspense>
          <RegisterForm />
        </Suspense>
      </AuthCard.Body>
      <AuthCard.Footer>
        Already an esteemed member?{" "}
        <Link href="/login" className="font-semibold text-brand hover:underline">
          Sign In
        </Link>
      </AuthCard.Footer>
    </AuthCard>
  );
}
