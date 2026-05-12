package com.slparcelauctions.backend.realty.slgroup;

import java.security.SecureRandom;

import org.springframework.stereotype.Component;

/**
 * Produces SL-group verification codes of the form {@code SLPA-XXXXXXXXXXXX} using a
 * 12-character alphabet that excludes visually ambiguous characters ({@code 0, O, 1, l, I}).
 * Codes are unique within the pool of pending {@link RealtyGroupSlGroup} rows by virtue
 * of the {@code UNIQUE(sl_group_uuid)} constraint preventing duplicate registrations; in
 * the unlikely event of a code collision between two pending rows for different SL groups,
 * one verification flow simply fails and the leader retries.
 *
 * <p>Alphabet: digits {@code 2-9} (omits {@code 0,1}) + uppercase {@code A-Z} minus
 * {@code I,L,O}. 31 characters. Search space {@code 31^12} = ~7.9e17 - collisions across
 * concurrent pending rows are vanishingly rare in practice.
 */
@Component
public class SlGroupVerificationCodeGenerator {

    private static final String ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ";    // 31 chars
    private static final int CODE_LEN = 12;
    private static final String PREFIX = "SLPA-";

    private final SecureRandom random;

    public SlGroupVerificationCodeGenerator() {
        this(new SecureRandom());
    }

    SlGroupVerificationCodeGenerator(SecureRandom random) {
        this.random = random;
    }

    public String generate() {
        StringBuilder sb = new StringBuilder(PREFIX.length() + CODE_LEN);
        sb.append(PREFIX);
        for (int i = 0; i < CODE_LEN; i++) {
            sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }
}
