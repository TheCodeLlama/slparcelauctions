package com.slparcelauctions.backend.sl;

import java.util.List;
import java.util.Optional;

/**
 * Static Mainland continent bounding boxes from the SL wiki's
 * <a href="https://wiki.secondlife.com/wiki/ContinentDetector">ContinentDetector</a>.
 * Replaces the unreliable Grid Survey API dependency. See spec 7.3.
 *
 * <p>Bounds are half-open: {@code x1 <= x < x2} and {@code y1 <= y < y2}.
 *
 * <p>When Linden Lab adds a new Mainland continent (rare event), add an
 * entry to {@link #BOXES}.
 */
public final class MainlandContinents {

    private record Continent(double x1, double x2, double y1, double y2, String name) {
        boolean contains(double x, double y) {
            return x >= x1 && x < x2 && y >= y1 && y < y2;
        }
    }

    private static final List<Continent> BOXES = List.of(
            new Continent(261888.0, 267776.0, 240640.0, 250368.0, "Bellisseria"),
            new Continent(267776.0, 281600.0, 243200.0, 258048.0, "Bellisseria"),
            new Continent(296704.0, 302080.0, 252928.0, 256768.0, "Sharp"),
            new Continent(257024.0, 266240.0, 229632.0, 240640.0, "Jeogeot"),
            new Continent(251392.0, 254208.0, 256000.0, 257792.0, "Bay City"),
            new Continent(254208.0, 265984.0, 250368.0, 259328.0, "Sansara"),
            new Continent(253696.0, 259840.0, 259328.0, 265472.0, "Heterocera"),
            new Continent(281344.0, 290560.0, 257280.0, 268288.0, "Satori"),
            new Continent(290048.0, 290816.0, 268288.0, 269824.0, "Western Blake Sea"),
            new Continent(290816.0, 297216.0, 265216.0, 271872.0, "Blake Sea"),
            new Continent(286976.0, 289792.0, 268032.0, 269312.0, "Nautilus City"),
            new Continent(283136.0, 293376.0, 268288.0, 276992.0, "Nautilus"),
            new Continent(281600.0, 296960.0, 276992.0, 281856.0, "Corsica"),
            new Continent(291840.0, 295936.0, 284672.0, 289536.0, "Gaeta I"),
            new Continent(296960.0, 304640.0, 276736.0, 281600.0, "Gaeta V"),
            new Continent(460032.0, 466432.0, 301824.0, 307456.0, "Zindra"),
            new Continent(461824.0, 464384.0, 307456.0, 310016.0, "Horizons"));

    public static Optional<String> continentAt(double gridX, double gridY) {
        return BOXES.stream()
                .filter(c -> c.contains(gridX, gridY))
                .map(Continent::name)
                .findFirst();
    }

    public static boolean isMainland(double gridX, double gridY) {
        return continentAt(gridX, gridY).isPresent();
    }

    private MainlandContinents() {}
}
