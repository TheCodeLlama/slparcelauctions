import { Star } from "@/components/ui/icons";

type ReputationStarsProps = {
  rating: number | null;
  reviewCount: number;
  label?: string;
};

export function ReputationStars({ rating, reviewCount, label }: ReputationStarsProps) {
  return (
    <div className="flex flex-col gap-1">
      {label && (
        <span className="text-label-sm font-bold uppercase tracking-widest text-on-surface-variant">
          {label}
        </span>
      )}
      {rating === null ? (
        <p className="text-body-md text-on-surface-variant">No ratings yet</p>
      ) : (
        <div className="flex items-center gap-2">
          <Star className="size-5 fill-primary text-primary" strokeWidth={1.5} />
          <span className="text-title-md font-bold">{rating.toFixed(1)}</span>
          <span className="text-body-sm text-on-surface-variant">
            ({reviewCount} review{reviewCount === 1 ? "" : "s"})
          </span>
        </div>
      )}
    </div>
  );
}
