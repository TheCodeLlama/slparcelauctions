"use client";

import type { ListingEligibleGroup } from "@/types/realty";

export interface ListAsGroupPickerProps {
  eligibleGroups: ListingEligibleGroup[];
  /** Selected group's publicId; null means Individual. */
  value: string | null;
  onChange: (groupPublicId: string | null) => void;
  /**
   * When false, the Individual radio is omitted — used by Realty Groups: E
   * for SL-group-owned parcels where personal listing is not a valid path
   * (you cannot personally own group-owned land). Defaults to true for
   * personal land / legacy callers.
   */
  showIndividual?: boolean;
}

export function ListAsGroupPicker({
  eligibleGroups,
  value,
  onChange,
  showIndividual = true,
}: ListAsGroupPickerProps) {
  if (eligibleGroups.length === 0) return null;

  return (
    <fieldset className="space-y-2">
      <legend className="font-medium text-sm mb-2">List as</legend>
      {showIndividual && (
        <label className="flex items-center gap-2">
          <input
            type="radio"
            name="list-as"
            checked={value === null}
            onChange={() => onChange(null)}
          />
          <span>Individual</span>
        </label>
      )}
      {eligibleGroups.map((g) => (
        <label key={g.publicId} className="flex items-center gap-2">
          <input
            type="radio"
            name="list-as"
            checked={value === g.publicId}
            onChange={() => onChange(g.publicId)}
          />
          <span>{g.name}</span>
        </label>
      ))}
    </fieldset>
  );
}
