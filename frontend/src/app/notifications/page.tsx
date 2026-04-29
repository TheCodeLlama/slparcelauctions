import { RequireAuth } from "@/components/auth/RequireAuth";
import { FeedShell } from "@/components/notifications/feed/FeedShell";

export default function NotificationsPage() {
  return (
    <RequireAuth>
      <FeedShell />
    </RequireAuth>
  );
}
