import Link from "next/link";

export default function GoodbyePage() {
  return (
    <div className="min-h-screen flex items-center justify-center p-4">
      <div className="bg-bg-muted rounded p-6 max-w-md text-center space-y-4">
        <h1 className="text-2xl font-semibold">Account deleted</h1>
        <p className="text-sm opacity-75">
          Your account has been deleted. Your auctions, bids, and reviews may remain
          visible as &quot;Deleted user&quot; to preserve the integrity of past records.
        </p>
        <p className="text-sm opacity-75">
          You can register a new account at any time.
        </p>
        <Link
          href="/register"
          className="inline-block px-4 py-2 bg-brand text-white rounded text-sm font-semibold"
        >
          Register a new account
        </Link>
      </div>
    </div>
  );
}
