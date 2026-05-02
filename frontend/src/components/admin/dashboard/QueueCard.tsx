import Link from "next/link";

type Tone = "fraud" | "warning";

const TONE_CLASSES: Record<Tone, { bg: string; border: string; value: string }> = {
  fraud: {
    bg: "bg-danger-bg",
    border: "border-danger-flat",
    value: "text-danger-flat",
  },
  warning: {
    bg: "bg-info-bg",
    border: "border-info-flat",
    value: "text-info-flat",
  },
};

type QueueCardProps = {
  label: string;
  value: number;
  tone: Tone;
  subtext: string;
  href?: string;
};

export function QueueCard({ label, value, tone, subtext, href }: QueueCardProps) {
  const t = TONE_CLASSES[tone];
  const inner = (
    <div className={`${t.bg} border ${t.border} rounded-lg p-4`}>
      <div className="flex items-start justify-between">
        <div>
          <div className="text-[11px] uppercase tracking-wide opacity-70">{label}</div>
          <div className={`text-3xl font-semibold leading-tight mt-1 ${t.value}`}>{value}</div>
        </div>
        {href && <div className="opacity-40 text-xs">→</div>}
      </div>
      <div className="text-[11px] opacity-50 mt-2">{subtext}</div>
    </div>
  );
  return href ? (
    <Link href={href} className="block hover:opacity-90">
      {inner}
    </Link>
  ) : (
    inner
  );
}
