package com.slparcelauctions.backend.notification.slim;

import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;

/**
 * Assembles the final SL IM text from (title, body, deeplink) components,
 * enforcing the SL {@code llInstantMessage} 1024-BYTE limit.
 *
 * <p>The deeplink, the {@code [SLPA] } prefix, and the title are non-negotiable
 * — they are reserved first and never trimmed. Only the body is ellipsizable.
 *
 * <p>SL truncates IMs that exceed 1024 bytes silently, from the end. If the
 * deeplink lands at the end (it does), it gets cleanly cut off. UTF-8 multi-byte
 * characters (CJK, emoji, accented Latin) push byte counts above char counts —
 * a 1023-character message can occupy 1500+ bytes. Hence the byte-aware budget.
 *
 * <p>Trim algorithm: progressively shorten the body by Java {@code char} from
 * the end until the assembled string fits. If a trim boundary lands on a high
 * surrogate (would split a UTF-16 surrogate pair, producing an invalid
 * sequence), back off one more char.
 */
@Component
public class SlImMessageBuilder {

    private static final int MAX_BYTES = 1024;
    private static final String PREFIX = "[SLPA] ";
    private static final String SEPARATOR = "\n\n";
    private static final String ELLIPSIS = "…";  // 3 bytes UTF-8

    public String assemble(String title, String body, String deeplink) {
        String candidate = PREFIX + title + SEPARATOR + body + SEPARATOR + deeplink;
        if (utf8Bytes(candidate) <= MAX_BYTES) {
            return candidate;
        }

        int reservedBytes = utf8Bytes(PREFIX + title + SEPARATOR + SEPARATOR + deeplink + ELLIPSIS);
        int availableForBody = MAX_BYTES - reservedBytes;

        if (availableForBody <= 0) {
            return assembleWithoutBody(title, deeplink);
        }

        int k = body.length();
        String trimmedBody = null;
        while (k > 0) {
            int boundary = k;
            if (Character.isHighSurrogate(body.charAt(boundary - 1))) {
                boundary -= 1;
            }
            if (boundary <= 0) {
                break;
            }
            String attempt = body.substring(0, boundary) + ELLIPSIS;
            if (utf8Bytes(attempt) <= availableForBody) {
                trimmedBody = attempt;
                break;
            }
            k = boundary - 1;
        }
        if (trimmedBody == null) {
            return assembleWithoutBody(title, deeplink);
        }
        return PREFIX + title + SEPARATOR + trimmedBody + SEPARATOR + deeplink;
    }

    private String assembleWithoutBody(String title, String deeplink) {
        // Last-resort: no body, just title + deeplink. If even that exceeds the
        // budget, trim the title. Deeplink stays whole.
        String candidate = PREFIX + title + SEPARATOR + deeplink;
        if (utf8Bytes(candidate) <= MAX_BYTES) {
            return candidate;
        }
        int reserved = utf8Bytes(PREFIX + SEPARATOR + deeplink + ELLIPSIS);
        int availableForTitle = MAX_BYTES - reserved;
        int k = title.length();
        while (k > 0) {
            int boundary = k;
            if (Character.isHighSurrogate(title.charAt(boundary - 1))) {
                boundary -= 1;
            }
            if (boundary <= 0) {
                break;
            }
            String attempt = title.substring(0, boundary) + ELLIPSIS;
            if (utf8Bytes(attempt) <= availableForTitle) {
                return PREFIX + attempt + SEPARATOR + deeplink;
            }
            k = boundary - 1;
        }
        // If even an empty-title fallback exceeds the budget, the deeplink itself
        // is too long. Return the deeplink alone — truncated to MAX_BYTES.
        byte[] bytes = deeplink.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) {
            return new String(bytes, 0, MAX_BYTES, StandardCharsets.UTF_8);
        }
        return deeplink;
    }

    private static int utf8Bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8).length;
    }
}
