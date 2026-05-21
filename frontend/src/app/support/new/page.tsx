import { NewSupportTicketForm } from "@/components/support/NewSupportTicketForm";

// Form posts user-typed data, so per-visit state lives entirely client-side;
// still opt out of Amplify build-time prerendering to mirror the rest of the
// support surface (see CLAUDE.md "Frontend SSR caveats").
export const dynamic = "force-dynamic";

export default function NewSupportTicketPage() {
  return <NewSupportTicketForm />;
}
