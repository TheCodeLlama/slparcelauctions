// export/realty-groups/mockData.ts

import type {
  GroupMember,
  GroupReview,
  RealtyGroupCard,
} from "./types";

const TAGLINES = [
  "Premier curators of waterfront and beach parcels across the mainland continents.",
  "Specialists in themed RP regions \u2014 medieval, Victorian, post-apocalyptic builds welcome.",
  "Family-run estate of homestead and quarter-region rentals, established 2017.",
  "Boutique brokerage focused on commercial frontage in high-traffic hubs.",
  "Mountain and forest specialists. Quiet residential covenants, established neighbors.",
  "Sky-platform experts. Low-lag platforms for shops, clubs, and event venues.",
  "Adult-region realty serving Zindra and surrounding moderate zones.",
  "Coastal estates with private docks, dive sites, and finished landscaping.",
  "Old-school mainland purveyors \u2014 telehub corners, road frontage, lighthouse rights.",
  "Themed-build collective. Steampunk, fantasy, and Asian architecture our specialties.",
  "Snow-region and chalet specialists. Aspen, Tahoe, and arctic builds.",
  "High-traffic mall and storefront brokerage. We do volume and we do it right.",
  "Modernist and minimalist sky homes. Curated for designers and architects.",
];

const NAMES: Array<[string, string]> = [
  ["Northcrest Realty", "northcrest"],
  ["Heron Cove Estates", "heron-cove"],
  ["Tall Oak Holdings", "tall-oak"],
  ["Lighthouse Brokers", "lighthouse-brokers"],
  ["Sable Vance Estates", "sable-vance"],
  ["Mossbridge Realty Group", "mossbridge"],
  ["Cypress Cove Collective", "cypress-cove"],
  ["Halloway and Sons", "halloway-sons"],
  ["Solano Realty", "solano"],
  ["Whitmer and Vance", "whitmer-vance"],
  ["Aria Land Trust", "aria-trust"],
  ["Linden Hills Brokerage", "linden-hills"],
  ["Verdant Coast Realty", "verdant-coast"],
  ["Mountain Vault Realty", "mountain-vault"],
  ["Twilight Estates", "twilight-estates"],
  ["Wend Realty Cooperative", "wend"],
  ["Shoreline Curators", "shoreline"],
  ["North Pole Properties", "north-pole"],
];

function makeGroup(i: number): RealtyGroupCard {
  const [name, slug] = NAMES[i % NAMES.length];
  const seed = i + 1;
  const founded = new Date(
    2017 + ((seed * 5) % 8),
    (seed * 11) % 12,
    ((seed * 7) % 27) + 1,
  );
  const memberCap = [4, 6, 8, 12, 16, 24][i % 6];
  const memberCount = Math.max(1, memberCap - (i % 4));
  const reviewCount = i % 7 === 3 ? 0 : 4 + ((i * 13) % 92);
  const avg = reviewCount === 0 ? null : 4.0 + ((i * 17) % 100) / 100;
  const activeListings = i % 5 === 0 ? 0 : 1 + ((i * 11) % 24);
  const sales = 12 + ((i * 23) % 240);
  return {
    publicId: "g" + (1000 + i),
    name,
    slug,
    tagline: TAGLINES[i % TAGLINES.length],
    logoUrl: null,
    coverUrl: null,
    foundedAt: founded.toISOString(),
    memberCount,
    memberSeatLimit: memberCap,
    activeListingsCount: activeListings,
    completedSalesCount: sales,
    hasVerifiedSlGroup: true,
    rating: { averageRating: avg, reviewCount },
  };
}

export const REALTY_GROUPS: RealtyGroupCard[] = Array.from(
  { length: 18 },
  (_, i) => makeGroup(i),
);

export const MOCK_LEADER: GroupMember = {
  id: "u1",
  name: "Aria Northcrest",
  rating: 4.9,
  sales: 142,
  memberSince: "2019",
};

export const MOCK_AGENTS: GroupMember[] = [
  { id: "u2", name: "Devon Halloway", rating: 4.7, sales: 87, memberSince: "2021" },
  { id: "u3", name: "Mira Kessel", rating: 5.0, sales: 211, memberSince: "2017" },
  { id: "u4", name: "Otto Wendel", rating: 4.6, sales: 53, memberSince: "2022" },
];

export const MOCK_REVIEWS: GroupReview[] = [
  {
    id: "r1",
    author: "Devon Hale",
    stars: 5,
    when: "1 wk ago",
    text: "Smooth handover, professional comms, paid the commission fairly. Would buy through them again.",
  },
  {
    id: "r2",
    author: "Mira Solano",
    stars: 5,
    when: "3 wks ago",
    text: "The listing agent was responsive and accurate about prim allocation. Closed in 36 hours.",
  },
  {
    id: "r3",
    author: "Otto Whitmer",
    stars: 4,
    when: "1 mo ago",
    text: "Good experience overall. Coords matched the listing exactly.",
  },
];
