package com.slparcelauctions.backend.auction.search;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import com.slparcelauctions.backend.parceltag.ParcelTag;

/**
 * Deterministic cache key derivation for the /auctions/search response
 * cache. Canonicalizes the query record - sorts set-typed fields, drops
 * nulls - then SHA-256 hashes the resulting string.
 *
 * <p>Two queries with the same filters in different Set iteration order
 * produce the same key.
 */
public final class SearchCacheKey {

    public static final String PREFIX = "slpa:search:";

    private SearchCacheKey() {}

    public static String keyFor(AuctionSearchQuery q) {
        StringBuilder sb = new StringBuilder();
        append(sb, "region", q.region());
        append(sb, "minArea", q.minArea());
        append(sb, "maxArea", q.maxArea());
        append(sb, "minPrice", q.minPrice());
        append(sb, "maxPrice", q.maxPrice());
        append(sb, "maturity", sortedStringSet(q.maturity()));
        append(sb, "tags", sortedStringSet(tagCodes(q.tags())));
        append(sb, "tagsMode", q.tagsMode());
        append(sb, "reserveStatus", q.reserveStatus());
        append(sb, "snipeProtection", q.snipeProtection());
        append(sb, "verificationTier", sortedStringSet(
                q.verificationTier() == null ? null
                        : q.verificationTier().stream().map(Enum::name).collect(Collectors.toSet())));
        append(sb, "endingWithinHours", q.endingWithinHours());
        append(sb, "nearRegion", q.nearRegion() == null ? null : q.nearRegion().toLowerCase());
        append(sb, "distance", q.distance());
        append(sb, "sellerId", q.sellerId());
        append(sb, "sort", q.sort());
        append(sb, "page", q.page());
        append(sb, "size", q.size());

        String canonical = sb.toString();
        return PREFIX + sha256Hex(canonical);
    }

    private static Set<String> tagCodes(Set<ParcelTag> tags) {
        if (tags == null || tags.isEmpty()) return null;
        return tags.stream().map(ParcelTag::getCode).collect(Collectors.toSet());
    }

    private static void append(StringBuilder sb, String name, Object value) {
        if (value == null) return;
        sb.append(name).append('=').append(value).append('|');
    }

    private static Set<String> sortedStringSet(Set<String> s) {
        if (s == null || s.isEmpty()) return null;
        return new TreeSet<>(s);
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
