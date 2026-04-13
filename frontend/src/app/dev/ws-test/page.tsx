// frontend/src/app/dev/ws-test/page.tsx
import { notFound } from "next/navigation";
import { WsTestHarness } from "@/components/dev/WsTestHarness";

export default function WsTestPage() {
  // Route 404s in production — /dev/** is a development-only surface.
  if (process.env.NODE_ENV === "production") {
    notFound();
  }
  return <WsTestHarness />;
}
