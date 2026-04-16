import { notFound } from "next/navigation";
import { PublicProfileView } from "@/components/user/PublicProfileView";

type Props = { params: Promise<{ id: string }> };

export default async function PublicProfilePage({ params }: Props) {
  const { id } = await params;
  const userId = Number(id);
  if (!Number.isInteger(userId) || userId <= 0) notFound();
  return <PublicProfileView userId={userId} />;
}
