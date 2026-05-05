import { cache } from "react";
import type { Metadata } from "next";
import { notFound } from "next/navigation";
import { userApi } from "@/lib/user/api";
import { PublicProfileView } from "@/components/user/PublicProfileView";

type Props = { params: Promise<{ publicId: string }> };

/**
 * Memoised public-profile fetch so {@link generateMetadata} and the page
 * body share one HTTP round-trip during SSR. Mirrors the pattern used on
 * {@code /auction/[publicId]}.
 */
const getPublicProfileCached = cache((publicId: string) => userApi.publicProfile(publicId));

const BIO_DESCRIPTION_LIMIT = 200;
const DEFAULT_DESCRIPTION = "Second Life parcel seller on SLParcels.";

/**
 * Truncate-at-word-boundary-with-ellipsis for the OG description. Clamps at
 * {@link BIO_DESCRIPTION_LIMIT} so Twitter / Facebook / LinkedIn all see a
 * compact preview regardless of how long the user's bio is.
 */
function truncateBio(bio: string, max: number): string {
  if (bio.length <= max) return bio;
  const slice = bio.slice(0, max - 1);
  // Trim any dangling whitespace from the cut so the ellipsis doesn't
  // follow a lonely space.
  return slice.replace(/\s+$/, "") + "…";
}

export async function generateMetadata({ params }: Props): Promise<Metadata> {
  const { publicId } = await params;
  if (!publicId) return { title: "Profile · SLParcels" };
  try {
    const user = await getPublicProfileCached(publicId);
    const displayName = user.displayName ?? "SLParcels user";
    const description = user.bio
      ? truncateBio(user.bio, BIO_DESCRIPTION_LIMIT)
      : DEFAULT_DESCRIPTION;
    const avatar = user.profilePicUrl ?? undefined;
    return {
      title: `${displayName} · SLParcels`,
      description,
      robots: { index: true, follow: true },
      openGraph: {
        title: displayName,
        description,
        images: avatar ? [avatar] : [],
        type: "profile",
      },
      twitter: {
        card: avatar ? "summary_large_image" : "summary",
      },
    };
  } catch {
    return { title: "Profile · SLParcels" };
  }
}

export default async function PublicProfilePage({ params }: Props) {
  const { publicId } = await params;
  if (!publicId) notFound();
  return <PublicProfileView userPublicId={publicId} />;
}
