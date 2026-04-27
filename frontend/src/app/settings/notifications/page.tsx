import { RequireAuth } from "@/components/auth/RequireAuth";
import { NotificationPreferencesPage } from "@/components/notifications/preferences/NotificationPreferencesPage";

export default function NotificationSettingsPage() {
  return (
    <RequireAuth>
      <NotificationPreferencesPage />
    </RequireAuth>
  );
}
