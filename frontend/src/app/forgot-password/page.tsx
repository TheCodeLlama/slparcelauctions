import type { Metadata } from "next";
import { PageHeader } from "@/components/layout/PageHeader";

export const metadata: Metadata = { title: "Forgot Password" };

export default function ForgotPasswordPage() {
  return (
    <PageHeader
      title="Forgot Password"
      subtitle="We'll send you a reset link."
    />
  );
}
