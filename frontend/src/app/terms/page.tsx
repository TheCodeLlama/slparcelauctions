import type { Metadata } from "next";
import { PageHeader } from "@/components/layout/PageHeader";

export const metadata: Metadata = { title: "Terms" };

export default function TermsPage() {
  return <PageHeader title="Terms of Service" subtitle="The rules of the road." />;
}
