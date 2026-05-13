import { GroupsBrowseClient } from "@/components/realty/browse/GroupsBrowseClient";

/**
 * Public realty-group directory. Spec section 7.1.
 *
 * {@code force-dynamic} per the SSR caveat in CLAUDE.md — search, sort, and
 * pagination state change per visit, so static prerendering would cache a
 * single page-zero snapshot and lie to every other visitor.
 */
export const dynamic = "force-dynamic";

export default function GroupsBrowseRoute() {
  return <GroupsBrowseClient />;
}
