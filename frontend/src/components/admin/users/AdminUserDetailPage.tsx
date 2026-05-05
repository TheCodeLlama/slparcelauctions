"use client";
import { useState } from "react";
import { useAdminUser } from "@/hooks/admin/useAdminUser";
import { UserProfileHeader } from "./UserProfileHeader";
import { UserStatsCards } from "./UserStatsCards";
import { UserTabsNav, type UserTab } from "./UserTabsNav";
import { ListingsTab } from "./tabs/ListingsTab";
import { BidsTab } from "./tabs/BidsTab";
import { CancellationsTab } from "./tabs/CancellationsTab";
import { ReportsTab } from "./tabs/ReportsTab";
import { FraudFlagsTab } from "./tabs/FraudFlagsTab";
import { ModerationTab } from "./tabs/ModerationTab";
import { WalletTab } from "./wallet/WalletTab";
import { UserActionsRail } from "./UserActionsRail";

type Props = {
  publicId: string;
};

export function AdminUserDetailPage({ publicId }: Props) {
  const [activeTab, setActiveTab] = useState<UserTab>("listings");
  const { data: user, isLoading, isError, refetch } = useAdminUser(publicId);

  if (isLoading) {
    return (
      <div className="py-12 text-sm text-fg-muted" data-testid="user-detail-loading">
        Loading user…
      </div>
    );
  }

  if (isError || !user) {
    return (
      <div className="py-12 text-sm text-danger" data-testid="user-detail-error">
        Could not load user. Refresh to retry.
      </div>
    );
  }

  return (
    <div
      className="grid grid-cols-[1fr_280px] gap-6 items-start"
      data-testid="user-detail-page"
    >
      <div className="min-w-0">
        <UserProfileHeader user={user} />
        <UserStatsCards stats={user} />
        <UserTabsNav active={activeTab} onChange={setActiveTab} />

        {activeTab === "listings" && <ListingsTab publicId={publicId} />}
        {activeTab === "bids" && <BidsTab publicId={publicId} />}
        {activeTab === "cancellations" && <CancellationsTab publicId={publicId} />}
        {activeTab === "reports" && <ReportsTab publicId={publicId} />}
        {activeTab === "fraudFlags" && <FraudFlagsTab publicId={publicId} />}
        {activeTab === "moderation" && <ModerationTab publicId={publicId} />}
        {activeTab === "wallet" && <WalletTab publicId={publicId} />}
      </div>

      <div className="sticky top-6">
        <UserActionsRail user={user} onRefresh={() => refetch()} />
      </div>
    </div>
  );
}
