type StatCardProps = {
  label: string;
  value: string | number;
  accent?: boolean;
};

export function StatCard({ label, value, accent }: StatCardProps) {
  return (
    <div className="bg-bg-muted border border-border-subtle rounded-lg p-4">
      <div className="text-[11px] opacity-60">{label}</div>
      <div className={`text-2xl font-semibold mt-1.5 ${accent ? "text-brand" : ""}`}>
        {value}
      </div>
    </div>
  );
}
