import { RequireAuth } from "@/components/auth/RequireAuth";
import { NotificationPreferencesPage } from "@/components/notifications/preferences/NotificationPreferencesPage";
import { DeleteAccountSection } from "@/components/settings/DeleteAccountSection";

export default function NotificationSettingsPage() {
  return (
    <RequireAuth>
      <NotificationPreferencesPage />
      <DeleteAccountSection />
    </RequireAuth>
  );
}
