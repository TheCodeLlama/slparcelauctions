"use client";

import type { ListingEligibleGroup } from "@/types/realty";

export interface ListAsGroupPickerProps {
  eligibleGroups: ListingEligibleGroup[];
  /** Selected group's publicId; null means Individual. */
  value: string | null;
  onChange: (groupPublicId: string | null) => void;
}

export function ListAsGroupPicker({
  eligibleGroups,
  value,
  onChange,
}: ListAsGroupPickerProps) {
  if (eligibleGroups.length === 0) return null;

  return (
    <fieldset className="space-y-2">
      <legend className="font-medium text-sm mb-2">List as</legend>
      <label className="flex items-center gap-2">
        <input
          type="radio"
          name="list-as"
          checked={value === null}
          onChange={() => onChange(null)}
        />
        <span>Individual</span>
      </label>
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
