import { RequireAuth } from "@/components/auth/RequireAuth";

export default function UsersLayout({ children }: { children: React.ReactNode }) {
  return <RequireAuth>{children}</RequireAuth>;
}
