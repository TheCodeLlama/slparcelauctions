package com.slparcelauctions.backend.realty.slug;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import com.slparcelauctions.backend.realty.RealtyGroupRepository;
import com.slparcelauctions.backend.realty.exception.ReservedSlugException;

import lombok.RequiredArgsConstructor;

/**
 * Derives a URL slug from a group name and resolves collisions against the active set.
 *
 * <p>Rule (per spec §3.6): lowercase, replace non-[a-z0-9]+ with `-`, trim leading/trailing `-`,
 * truncate at 60 chars at a `-` boundary if possible, fall back to a publicId-derived placeholder
 * for empty results, then append `-2`, `-3`, … until free (capping total at 80 chars).
 */
@Service
@RequiredArgsConstructor
public class RealtyGroupSlugFactory {

    private static final int BASE_MAX = 60;
    private static final int TOTAL_MAX = 80;
    private static final Pattern NON_ALNUM = Pattern.compile("[^a-z0-9]+");
    private static final Pattern EDGE_DASHES = Pattern.compile("^-+|-+$");

    /**
     * Slugs reserved by the {@code /groups} URL namespace. {@code /groups/new} is the
     * create-group page, {@code /groups/me} is the caller's own groups, and
     * {@code /groups/invitations} is the pending-invitations inbox. A user-named group
     * landing on any of these would shadow the application route.
     *
     * <p>Spec section 6.3: enforced at the slug factory so both create and rename
     * funnel through the same check.
     */
    private static final Set<String> RESERVED_SLUGS = Set.of("new", "me", "invitations");

    private final RealtyGroupRepository repo;

    /**
     * Pure name-to-slug transformation without collision resolution. Returns empty string for
     * an input with no usable characters (e.g. all non-ASCII).
     */
    public String fromName(String name) {
        if (name == null) return "";
        String lower = name.toLowerCase(Locale.ROOT);
        String dashed = NON_ALNUM.matcher(lower).replaceAll("-");
        String trimmed = EDGE_DASHES.matcher(dashed).replaceAll("");
        if (trimmed.length() <= BASE_MAX) return trimmed;
        // Truncate at a `-` boundary when possible.
        int cut = trimmed.lastIndexOf('-', BASE_MAX);
        if (cut > BASE_MAX / 2) {
            return trimmed.substring(0, cut);
        }
        return trimmed.substring(0, BASE_MAX);
    }

    /**
     * Derive a collision-free slug for a group, optionally excluding the row being renamed.
     * Returns a placeholder ("group") for empty bases; the caller should patch with a
     * publicId-derived fallback after entity persist if needed.
     */
    public String derive(String name, Long excludeGroupId) {
        String base = fromName(name);
        if (RESERVED_SLUGS.contains(base)) {
            throw new ReservedSlugException(base);
        }
        if (base.isEmpty()) base = "group";

        if (countCollisions(base, excludeGroupId) == 0) return base;

        for (int i = 2; i < 10_000; i++) {
            String suffix = "-" + i;
            String candidate;
            if (base.length() + suffix.length() > TOTAL_MAX) {
                int keep = TOTAL_MAX - suffix.length();
                candidate = base.substring(0, Math.max(1, keep)) + suffix;
            } else {
                candidate = base + suffix;
            }
            if (countCollisions(candidate, excludeGroupId) == 0) return candidate;
        }
        throw new IllegalStateException("Slug collision space exhausted for base=" + base);
    }

    private long countCollisions(String slug, Long excludeId) {
        return excludeId == null
            ? repo.countActiveBySlug(slug)
            : repo.countOtherActiveBySlug(slug, excludeId);
    }
}
