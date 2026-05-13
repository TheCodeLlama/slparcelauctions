// export/realty-groups/types.ts

export interface GroupRating {
  averageRating: number | null;
  reviewCount: number;
}

export interface RealtyGroupCard {
  publicId: string;
  name: string;
  slug: string;
  tagline: string;
  logoUrl: string | null;
  coverUrl: string | null;
  foundedAt: string;
  memberCount: number;
  memberSeatLimit: number;
  activeListingsCount: number;
  completedSalesCount: number;
  hasVerifiedSlGroup: boolean;
  rating: GroupRating;
}

export interface GroupMember {
  id: string;
  name: string;
  rating: number;
  sales: number;
  memberSince: string;
}

export interface GroupReview {
  id: string;
  author: string;
  stars: number;
  when: string;
  text: string;
}

export type GroupCardLayout = "standard" | "compact" | "cover";
export type GroupSidebarPlacement = "left" | "right" | "hidden";

export type GroupsSortKey =
  | "ACTIVE_LISTINGS"
  | "AGE"
  | "NAME"
  | "RATING"
  | "SALES";

export type SortDirection = "asc" | "desc";
