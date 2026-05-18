package com.slparcelauctions.backend.sl;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public final class SlurlBuilder {
    private SlurlBuilder() {}
    private static long pos(Double v) { return (v == null || v == 0.0) ? 128 : Math.round(v); }
    private static long z(Double v) { return v == null ? 0 : Math.round(v); }
    public static String mapUrl(String region, Double x, Double y, Double zc) {
        String r = URLEncoder.encode(region == null ? "" : region, StandardCharsets.UTF_8).replace("+", "%20");
        return "https://maps.secondlife.com/secondlife/" + r + "/" + pos(x) + "/" + pos(y) + "/" + z(zc);
    }
    public static String viewerUrl(String region, Double x, Double y, Double zc) {
        return "secondlife:///app/teleport/" + (region == null ? "" : region) + "/" + pos(x) + "/" + pos(y) + "/" + z(zc);
    }
}
