// frontend/src/app/register/page.tsx
"use client";

import Link from "next/link";
import { AuthCard } from "@/components/auth/AuthCard";
import { RegisterForm } from "@/components/auth/RegisterForm";

export default function RegisterPage() {
  return (
    <AuthCard>
      <AuthCard.Title>Create Your Account</AuthCard.Title>
      <AuthCard.Subtitle>Join the digital curator.</AuthCard.Subtitle>
      <AuthCard.Body>
        <RegisterForm />
      </AuthCard.Body>
      <AuthCard.Footer>
        Already an esteemed member?{" "}
        <Link href="/login" className="font-semibold text-primary hover:underline">
          Sign In
        </Link>
      </AuthCard.Footer>
    </AuthCard>
  );
}
