import type { Metadata } from "next";
import { PageHeader } from "@/components/layout/PageHeader";

export const metadata: Metadata = { title: "Sign In" };

export default function LoginPage() {
  return <PageHeader title="Sign In" subtitle="Welcome back to SLPA." />;
}
