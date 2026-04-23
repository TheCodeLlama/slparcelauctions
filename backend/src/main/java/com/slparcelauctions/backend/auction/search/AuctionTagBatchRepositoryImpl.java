package com.slparcelauctions.backend.auction.search;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.slparcelauctions.backend.parceltag.ParcelTag;
import com.slparcelauctions.backend.parceltag.ParcelTagRepository;

import lombok.RequiredArgsConstructor;

/**
 * Two-step batch loader for auction → tag-set hydration.
 *
 * <ol>
 *   <li>One JDBC pass over {@code auction_tags(auction_id, tag_id)} for
 *       the page's auction ids — yields {@code Map<auctionId,
 *       Set<tagId>>}.</li>
 *   <li>One JPA call to {@link ParcelTagRepository#findAllById} on the
 *       union of tag ids — managed entities, properly cached.</li>
 * </ol>
 *
 * <p>Splitting the work this way keeps the SQL trivially indexable
 * (uses the {@code auction_tags_pkey (auction_id, tag_id)} btree
 * directly) and lets Hibernate's L2 cache participate in the tag-row
 * fetch on the second leg.
 */
@Repository
@RequiredArgsConstructor
public class AuctionTagBatchRepositoryImpl implements AuctionTagBatchRepository {

    private final NamedParameterJdbcTemplate jdbc;
    private final ParcelTagRepository parcelTagRepository;

    @Override
    public Map<Long, Set<ParcelTag>> findTagsGrouped(Collection<Long> auctionIds) {
        if (auctionIds == null || auctionIds.isEmpty()) {
            return Map.of();
        }

        String sql = "SELECT auction_id, tag_id FROM auction_tags WHERE auction_id IN (:ids)";
        MapSqlParameterSource params = new MapSqlParameterSource("ids", auctionIds);

        Map<Long, Set<Long>> idMap = new HashMap<>();
        Set<Long> allTagIds = new HashSet<>();
        jdbc.query(sql, params, (rs, i) -> {
            long auctionId = rs.getLong("auction_id");
            long tagId = rs.getLong("tag_id");
            idMap.computeIfAbsent(auctionId, k -> new HashSet<>()).add(tagId);
            allTagIds.add(tagId);
            return null;
        });

        if (idMap.isEmpty()) {
            return Map.of();
        }

        Map<Long, ParcelTag> tagsById = new HashMap<>();
        for (ParcelTag t : parcelTagRepository.findAllById(allTagIds)) {
            tagsById.put(t.getId(), t);
        }

        Map<Long, Set<ParcelTag>> out = new HashMap<>(idMap.size());
        idMap.forEach((auctionId, tagIds) -> {
            Set<ParcelTag> tags = new HashSet<>(tagIds.size());
            for (Long tagId : tagIds) {
                ParcelTag t = tagsById.get(tagId);
                if (t != null) {
                    tags.add(t);
                }
            }
            out.put(auctionId, tags);
        });
        return out;
    }
}
