/**
 * Per-browser dismissal state for the site-wide wallet-terms nudge banner
 * (see WalletTermsBanner). This is purely a UI nudge suppressor — the real
 * acceptance state lives server-side on the user. The key is namespaced by
 * the accepted terms version so a future terms bump re-shows the banner to
 * users who previously dismissed it, with no server change.
 *
 * The version is passed in by the caller rather than imported here so this
 * module has no dependency on the modal component and stays trivially
 * unit-testable. All access is guarded for SSR / the Amplify build, where
 * `window` is undefined.
 */
export function termsBannerDismissalKey(version: string): string {
  return `slpa.walletTermsBannerDismissed.v${version}`;
}

export function isTermsBannerDismissed(version: string): boolean {
  if (typeof window === "undefined") return false;
  try {
    return window.localStorage.getItem(termsBannerDismissalKey(version)) === "1";
  } catch {
    // localStorage can throw in private mode / when disabled — treat as
    // not-dismissed so the banner still nudges.
    return false;
  }
}

export function dismissTermsBanner(version: string): void {
  if (typeof window === "undefined") return;
  try {
    window.localStorage.setItem(termsBannerDismissalKey(version), "1");
  } catch {
    // Best-effort: a failed write just means the banner shows again next
    // load. Acceptable for a nudge.
  }
}
