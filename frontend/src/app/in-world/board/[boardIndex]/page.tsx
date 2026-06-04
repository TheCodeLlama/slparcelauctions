import { fetchFeaturedBoard } from "@/lib/api/inWorldBoards";
import { FeaturedBoardCycler } from "@/components/inworld/FeaturedBoardCycler";
import { PlaceholderBoardView } from "@/components/inworld/PlaceholderBoardView";

export const dynamic = "force-dynamic";

interface Props {
  params: Promise<{ boardIndex: string }>;
}

export default async function InWorldBoardPage({ params }: Props) {
  const { boardIndex } = await params;
  const idx = Number.parseInt(boardIndex, 10);
  if (Number.isNaN(idx) || idx < 1 || idx > 13) {
    return <PlaceholderBoardView />;
  }
  let payload;
  try {
    payload = await fetchFeaturedBoard(idx);
  } catch {
    return <PlaceholderBoardView />;
  }
  if (payload.source === "PLACEHOLDER" || payload.listings.length === 0) {
    return <PlaceholderBoardView />;
  }
  return (
    <FeaturedBoardCycler
      listings={payload.listings}
      cycleSeconds={payload.cycleSeconds}
    />
  );
}
