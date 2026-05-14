// export/realty-groups/components/FilterGroup.tsx
"use client";

import type { ReactNode } from "react";

interface FilterGroupProps {
  title: string;
  children: ReactNode;
}

export function FilterGroup({ title, children }: FilterGroupProps) {
  return (
    <div className="mb-6">
      <div className="text-[11px] font-semibold tracking-[0.06em] uppercase text-fg-subtle mb-2.5">
        {title}
      </div>
      <div className="flex flex-col gap-2">{children}</div>
    </div>
  );
}
