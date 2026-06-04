package com.slparcelauctions.backend.promotion;

import java.util.Map;

import org.springframework.stereotype.Service;

/**
 * Pure least-loaded board selection. No DB, no transactions, no Spring
 * dependencies beyond {@code @Service} for DI. Callers are responsible
 * for supplying the canonical per-board active-row counts (typically
 * derived from {@code FeaturedBoardSlotRepository.allActive()}).
 */
@Service
public class FeaturedBoardAssignmentService {

    public record Assignment(int boardIndex, int position) {}

    /**
     * Pick the board with the fewest active rows. Boards not in the counts
     * map are treated as empty. Tiebreak: lowest board index wins.
     *
     * @param slotCount  total active board count (config-driven, 1..13).
     * @param perBoardCounts  map of boardIndex -> active row count.
     * @return the board to assign to and the {@code position} the new row
     *         should take (== current count for that board, since rows
     *         are appended).
     */
    public Assignment assign(int slotCount, Map<Integer, Integer> perBoardCounts) {
        if (slotCount < 1) {
            throw new IllegalArgumentException("slotCount must be >= 1, got " + slotCount);
        }
        int bestBoard = 1;
        int bestCount = Integer.MAX_VALUE;
        for (int b = 1; b <= slotCount; b++) {
            int count = perBoardCounts.getOrDefault(b, 0);
            if (count < bestCount) {
                bestBoard = b;
                bestCount = count;
            }
        }
        return new Assignment(bestBoard, bestCount);
    }
}
