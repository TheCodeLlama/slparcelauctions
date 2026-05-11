const COMMISSION_RATE = 0.05;

function floorLindens(bid: number, rate: number): number {
  return Math.floor(bid * rate);
}

export interface AgentFeePreviewProps {
  startingBid: number;
  groupName: string;
  agentFeeRate: number;
}

export function AgentFeePreview({
  startingBid,
  groupName,
  agentFeeRate,
}: AgentFeePreviewProps) {
  if (startingBid <= 0) return null;

  const commission = floorLindens(startingBid, COMMISSION_RATE);
  const agentFee = floorLindens(startingBid, agentFeeRate);
  const payout = startingBid - commission - agentFee;
  const ratePct = (agentFeeRate * 100).toFixed(agentFeeRate < 0.01 ? 2 : 1).replace(/\.0$/, "");

  return (
    <p className="text-sm text-gray-600 mt-2">
      If this lists at L${startingBid.toLocaleString()}, you{"'"}ll receive approximately{" "}
      <strong>L${payout.toLocaleString()}</strong> after platform commission (5%) and{" "}
      {groupName} agent fee ({ratePct}%).
    </p>
  );
}
