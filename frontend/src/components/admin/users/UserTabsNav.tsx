import { cn } from "@/lib/cn";

export type UserTab = "listings" | "bids" | "cancellations" | "reports" | "fraudFlags" | "moderation";

const TABS: Array<{ id: UserTab; label: string }> = [
  { id: "listings", label: "Listings" },
  { id: "bids", label: "Bids" },
  { id: "cancellations", label: "Cancellations" },
  { id: "reports", label: "Reports" },
  { id: "fraudFlags", label: "Fraud flags" },
  { id: "moderation", label: "Moderation" },
];

type Props = {
  active: UserTab;
  counts?: Partial<Record<UserTab, number>>;
  onChange: (tab: UserTab) => void;
};

export function UserTabsNav({ active, counts, onChange }: Props) {
  return (
    <div
      className="flex border-b border-border-subtle mb-4 overflow-x-auto"
      data-testid="user-tabs-nav"
      role="tablist"
    >
      {TABS.map(({ id, label }) => {
        const count = counts?.[id];
        return (
          <button
            key={id}
            role="tab"
            aria-selected={active === id}
            data-testid={`tab-${id}`}
            onClick={() => onChange(id)}
            className={cn(
              "flex items-center gap-1.5 px-4 py-2.5 text-sm whitespace-nowrap border-b-2 transition-colors",
              active === id
                ? "border-brand text-brand font-medium"
                : "border-transparent text-fg-muted hover:text-fg"
            )}
          >
            {label}
            {count !== undefined && count > 0 && (
              <span className="bg-bg-hover text-fg-muted rounded-full px-1.5 py-0.5 text-[10px]">
                {count}
              </span>
            )}
          </button>
        );
      })}
    </div>
  );
}
