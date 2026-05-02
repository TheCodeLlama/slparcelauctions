"use client";
import { useState, useEffect } from "react";
import { useNotificationPreferences } from "@/hooks/notifications/useNotificationPreferences";
import { useUpdateNotificationPreferences } from "@/hooks/notifications/useUpdateNotificationPreferences";
import { ChannelInfoBanner } from "./ChannelInfoBanner";
import { MasterMuteRow } from "./MasterMuteRow";
import { GroupToggleRow } from "./GroupToggleRow";
import {
  EDITABLE_GROUPS, GROUP_LABELS, GROUP_SUBTEXT,
  type EditableGroup, type PreferencesDto,
} from "@/lib/notifications/preferencesTypes";
import { LoadingSpinner } from "@/components/ui/LoadingSpinner";

export function NotificationPreferencesPage() {
  const { data, isPending } = useNotificationPreferences();
  const update = useUpdateNotificationPreferences();

  // Local state mirrors server state for immediate UI feedback. Master mute
  // does NOT mutate per-group state — disabled is purely presentational.
  const [local, setLocal] = useState<PreferencesDto | null>(null);

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- server preferences are the external source of truth; seeding local optimistic state on first fetch so edits are instant without round-trips.
    if (data) setLocal(data);
  }, [data]);

  if (isPending || !local) {
    return <div className="flex justify-center p-8"><LoadingSpinner /></div>;
  }

  const handleMasterMute = (next: boolean) => {
    const updated = { ...local, slImMuted: next };
    setLocal(updated);
    update.mutate(updated);
  };

  const handleGroupToggle = (group: EditableGroup, next: boolean) => {
    const updated = {
      ...local,
      slIm: { ...local.slIm, [group]: next },
    };
    setLocal(updated);
    update.mutate(updated);
  };

  return (
    <div>
      <ChannelInfoBanner />

      <MasterMuteRow value={local.slImMuted} onChange={handleMasterMute} />

      <div className="mb-2 text-[11px] font-medium uppercase tracking-wide text-fg-muted font-semibold">
        Send via SL IM
      </div>

      <div className="bg-bg border border-border rounded-xl">
        {EDITABLE_GROUPS.map((g) => (
          <GroupToggleRow
            key={g}
            group={g}
            label={GROUP_LABELS[g]}
            subtext={GROUP_SUBTEXT[g]}
            value={local.slIm[g]}
            mutedDisabled={local.slImMuted}
            onChange={(next) => handleGroupToggle(g, next)}
          />
        ))}
      </div>
    </div>
  );
}
