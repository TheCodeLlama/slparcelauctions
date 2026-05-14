import { redirect } from "next/navigation";

/**
 * Landing route for {@code /groups/[slug]/manage}. The management area's
 * default tab is "Profile"; this route is a server-side 307 to the profile
 * sub-page so the URL bar always lands on a concrete tab. The "Manage group"
 * button on the public profile already targets {@code /manage/profile}
 * directly; this redirect catches direct typing + back-button navigation.
 */
export default async function ManageRootRoute({
  params,
}: {
  params: Promise<{ slug: string }>;
}) {
  const { slug } = await params;
  redirect(`/groups/${encodeURIComponent(slug)}/manage/profile`);
}
