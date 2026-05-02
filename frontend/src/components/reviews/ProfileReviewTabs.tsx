"use client";

import { Tab, TabGroup, TabList, TabPanel, TabPanels } from "@headlessui/react";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { Suspense, useCallback, useMemo } from "react";
import { cn } from "@/lib/cn";
import { RatingSummary } from "./RatingSummary";
import { ReviewList } from "./ReviewList";

/**
 * URL-synced key for the active tab. The backend enum is SELLER/BUYER but
 * the URL uses the lowercase variant for friendliness. Kept as a map here
 * rather than a {@code .toLowerCase()} call so TypeScript catches any
 * typo at the call site.
 */
const TAB_SLUGS = ["seller", "buyer"] as const;
type TabSlug = (typeof TAB_SLUGS)[number];

/**
 * Parse a raw {@code ?tab=…} value into one of our two slugs. Invalid
 * values fall back to "seller" (the default active tab per spec §8.2) so
 * malformed deep-links don't break the surface.
 */
function parseTab(raw: string | null): TabSlug {
  if (raw === "buyer") return "buyer";
  return "seller";
}

/**
 * Parse a raw {@code ?page=…} value into a 0-indexed non-negative integer.
 * Garbage values collapse to 0 so the fallback is "first page" rather than
 * "error".
 */
function parsePage(raw: string | null): number {
  if (!raw) return 0;
  const n = Number(raw);
  if (!Number.isInteger(n) || n < 0) return 0;
  return n;
}

export interface ProfileReviewTabsProps {
  userId: number;
  avgSellerRating: number | null;
  avgBuyerRating: number | null;
  totalSellerReviews: number;
  totalBuyerReviews: number;
  className?: string;
}

function ProfileReviewTabsContent({
  userId,
  avgSellerRating,
  avgBuyerRating,
  totalSellerReviews,
  totalBuyerReviews,
  className,
}: ProfileReviewTabsProps) {
  const router = useRouter();
  const pathname = usePathname();
  const searchParams = useSearchParams();

  const activeTab = parseTab(searchParams?.get("tab") ?? null);
  // Per-tab page keys so switching tabs preserves each tab's pagination
  // state. `sellerPage` / `buyerPage` live in the URL so deep-links work.
  const sellerPage = parsePage(searchParams?.get("sellerPage") ?? null);
  const buyerPage = parsePage(searchParams?.get("buyerPage") ?? null);

  const syncParam = useCallback(
    (key: string, value: string | null) => {
      const next = new URLSearchParams(searchParams?.toString() ?? "");
      if (value === null || value === "") {
        next.delete(key);
      } else {
        next.set(key, value);
      }
      const qs = next.toString();
      const url = qs ? `${pathname}?${qs}` : pathname;
      router.replace(url, { scroll: false });
    },
    [pathname, router, searchParams],
  );

  const handleTabChange = useCallback(
    (index: number) => {
      const nextSlug = TAB_SLUGS[index] ?? "seller";
      // Default tab omits the param to keep URLs tidy for the common case.
      syncParam("tab", nextSlug === "seller" ? null : nextSlug);
    },
    [syncParam],
  );

  const handleSellerPageChange = useCallback(
    (page: number) => {
      syncParam("sellerPage", page === 0 ? null : String(page));
    },
    [syncParam],
  );

  const handleBuyerPageChange = useCallback(
    (page: number) => {
      syncParam("buyerPage", page === 0 ? null : String(page));
    },
    [syncParam],
  );

  const selectedIndex = useMemo(
    () => TAB_SLUGS.indexOf(activeTab),
    [activeTab],
  );

  return (
    <TabGroup
      selectedIndex={selectedIndex}
      onChange={handleTabChange}
      className={cn("flex flex-col gap-4", className)}
    >
      <TabList
        aria-label="Reviews by role"
        className="flex gap-1 border-b border-border-subtle"
      >
        <Tab
          data-testid="profile-review-tab-seller"
          className={({ selected }) =>
            cn(
              "px-4 py-2 text-sm font-medium transition-colors focus:outline-none",
              selected
                ? "text-brand border-b-2 border-brand"
                : "text-fg-muted hover:text-fg",
            )
          }
        >
          As Seller
        </Tab>
        <Tab
          data-testid="profile-review-tab-buyer"
          className={({ selected }) =>
            cn(
              "px-4 py-2 text-sm font-medium transition-colors focus:outline-none",
              selected
                ? "text-brand border-b-2 border-brand"
                : "text-fg-muted hover:text-fg",
            )
          }
        >
          As Buyer
        </Tab>
      </TabList>
      <TabPanels>
        <TabPanel
          data-testid="profile-review-panel-seller"
          className="flex flex-col gap-4 focus:outline-none"
        >
          <RatingSummary
            rating={avgSellerRating}
            reviewCount={totalSellerReviews}
            size="md"
          />
          <ReviewList
            userId={userId}
            role="SELLER"
            page={sellerPage}
            onPageChange={handleSellerPageChange}
          />
        </TabPanel>
        <TabPanel
          data-testid="profile-review-panel-buyer"
          className="flex flex-col gap-4 focus:outline-none"
        >
          <RatingSummary
            rating={avgBuyerRating}
            reviewCount={totalBuyerReviews}
            size="md"
          />
          <ReviewList
            userId={userId}
            role="BUYER"
            page={buyerPage}
            onPageChange={handleBuyerPageChange}
          />
        </TabPanel>
      </TabPanels>
    </TabGroup>
  );
}

/**
 * Tabbed profile reviews section. Two tabs ("As Seller" default / "As
 * Buyer") each hosting an independently paginated {@link ReviewList}. Tab
 * selection and per-tab page state sync to the URL via
 * {@code ?tab=seller|buyer}, {@code ?sellerPage=N}, {@code ?buyerPage=N}
 * so deep-links and browser history work.
 *
 * <p>The inner component reads {@code useSearchParams}, which requires a
 * {@code <Suspense>} boundary in Next.js 16 to keep the static-prerender
 * pipeline from bailing. The wrapper owns the boundary so any caller can
 * drop {@code <ProfileReviewTabs>} into a server component without
 * thinking about it.
 */
export function ProfileReviewTabs(props: ProfileReviewTabsProps) {
  return (
    <Suspense
      fallback={
        <div className="flex flex-col gap-4">
          <div className="h-10 border-b border-border-subtle" />
          <div className="h-32" aria-hidden="true" />
        </div>
      }
    >
      <ProfileReviewTabsContent {...props} />
    </Suspense>
  );
}
