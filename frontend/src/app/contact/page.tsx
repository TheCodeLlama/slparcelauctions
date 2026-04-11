import type { Metadata } from "next";
import { PageHeader } from "@/components/layout/PageHeader";

export const metadata: Metadata = { title: "Contact" };

export default function ContactPage() {
  return <PageHeader title="Contact" subtitle="Get in touch with the SLPA team." />;
}
