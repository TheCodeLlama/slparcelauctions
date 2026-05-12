package com.slparcelauctions.backend.realty.slgroup;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

class SlGroupVerificationCodeGeneratorTest {

    private final SlGroupVerificationCodeGenerator gen =
            new SlGroupVerificationCodeGenerator(new SecureRandom());

    @Test
    void generatedCodeHasExpectedShape() {
        String code = gen.generate();
        assertThat(code).startsWith("SLPA-");
        assertThat(code).hasSize(5 + 12);
        assertThat(code.substring(5)).matches("[2-9A-HJ-NP-Z]{12}");
    }

    @Test
    void generatesUniqueCodesAcrossMultipleCalls() {
        Set<String> codes = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            codes.add(gen.generate());
        }
        // 100 from a 31^12 space -- collision probability is negligible
        assertThat(codes).hasSize(100);
    }
}
