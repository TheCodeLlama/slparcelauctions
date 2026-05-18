import { Header } from "./Header";
import { Footer } from "./Footer";
import { WalletTermsBanner } from "@/components/wallet/WalletTermsBanner";

export function AppShell({ children }: { children: React.ReactNode }) {
  return (
    <div className="flex min-h-screen flex-col">
      <Header />
      <WalletTermsBanner />
      <main className="flex-1">{children}</main>
      <Footer />
    </div>
  );
}
