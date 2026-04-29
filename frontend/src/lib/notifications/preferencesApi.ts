import { api } from "@/lib/api";
import type { PreferencesDto } from "./preferencesTypes";

export async function getNotificationPreferences(): Promise<PreferencesDto> {
  return api.get<PreferencesDto>("/api/v1/users/me/notification-preferences");
}

export async function putNotificationPreferences(
  body: PreferencesDto
): Promise<PreferencesDto> {
  return api.put<PreferencesDto>(
    "/api/v1/users/me/notification-preferences",
    body
  );
}
