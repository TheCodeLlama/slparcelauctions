import type { Metadata } from "next";
import { PageHeader } from "@/components/layout/PageHeader";

export const metadata: Metadata = { title: "Register" };

export default function RegisterPage() {
  return <PageHeader title="Register" subtitle="Create your SLPA account." />;
}
