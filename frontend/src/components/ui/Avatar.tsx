import Image from "next/image";
import { cn } from "@/lib/cn";

type AvatarSize = "xs" | "sm" | "md" | "lg" | "xl";

type AvatarProps = {
  src?: string;
  alt: string;
  name?: string;
  size?: AvatarSize;
  cacheBust?: string | number;
  className?: string;
};

const sizeMap: Record<AvatarSize, { px: number; class: string }> = {
  xs: { px: 24, class: "size-6 text-label-sm" },
  sm: { px: 32, class: "size-8 text-label-md" },
  md: { px: 40, class: "size-10 text-label-lg" },
  lg: { px: 56, class: "size-14 text-title-md" },
  xl: { px: 80, class: "size-20 text-title-lg" },
};

function initialsFromName(name?: string): string {
  if (!name) return "?";
  const parts = name.trim().split(/\s+/).filter(Boolean);
  if (parts.length === 0) return "?";
  if (parts.length === 1) return parts[0][0].toUpperCase();
  return (parts[0][0] + parts[1][0]).toUpperCase();
}

export function Avatar({
  src,
  alt,
  name,
  size = "md",
  cacheBust,
  className,
}: AvatarProps) {
  const { px, class: sizeClass } = sizeMap[size];

  if (src) {
    return (
      <Image
        key={cacheBust !== undefined ? `${src}-${cacheBust}` : src}
        src={src}
        alt={alt}
        width={px}
        height={px}
        className={cn("rounded-full object-cover", className)}
      />
    );
  }

  return (
    <div
      role="img"
      aria-label={alt}
      className={cn(
        "rounded-full bg-tertiary-container text-on-tertiary-container font-semibold inline-flex items-center justify-center",
        sizeClass,
        className
      )}
    >
      <span aria-hidden="true">{initialsFromName(name)}</span>
    </div>
  );
}
