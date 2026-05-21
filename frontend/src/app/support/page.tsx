import { SupportTicketList } from "@/components/support/SupportTicketList";

// Visit-specific content (status filters, last-message timestamps) — see
// `Frontend SSR caveats` in CLAUDE.md: any page whose data changes per visit
// must opt out of Amplify build-time prerendering so one bad-shape response
// can't fail the whole build.
export const dynamic = "force-dynamic";

export default function SupportPage() {
  return <SupportTicketList />;
}
