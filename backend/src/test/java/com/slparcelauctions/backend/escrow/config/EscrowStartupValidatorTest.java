package com.slparcelauctions.backend.escrow.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import com.slparcelauctions.backend.escrow.terminal.EscrowConfigProperties;

/**
 * Standalone unit test for the startup validator. No Spring context — we
 * construct {@link EscrowConfigProperties} via its canonical constructor and
 * invoke the package-private {@code validate()} hook directly. Covers the
 * three bands:
 *
 * <ul>
 *   <li>empty / blank secret → IllegalStateException</li>
 *   <li>secret shorter than 16 chars → IllegalStateException</li>
 *   <li>16+ char secret → passes</li>
 * </ul>
 */
class EscrowStartupValidatorTest {

    private static EscrowConfigProperties propsWithSecret(String secret) {
        return new EscrowConfigProperties(
                secret,
                Duration.ofMinutes(15),
                Duration.ofMinutes(5),
                5,
                Duration.ofHours(24));
    }

    @Test
    void emptySecret_throws() {
        EscrowStartupValidator v = new EscrowStartupValidator(propsWithSecret(""));
        assertThatIllegalStateException()
                .isThrownBy(v::validate)
                .withMessageContaining("must be set");
    }

    @Test
    void blankSecret_throws() {
        EscrowStartupValidator v = new EscrowStartupValidator(propsWithSecret("    "));
        assertThatIllegalStateException()
                .isThrownBy(v::validate)
                .withMessageContaining("must be set");
    }

    @Test
    void shortSecret_throws() {
        EscrowStartupValidator v = new EscrowStartupValidator(propsWithSecret("short-secret"));
        assertThatIllegalStateException()
                .isThrownBy(v::validate)
                .withMessageContaining("at least 16 characters");
    }

    @Test
    void validSecret_passes() {
        EscrowStartupValidator v = new EscrowStartupValidator(
                propsWithSecret("this-is-a-valid-production-secret"));
        assertThatCode(v::validate).doesNotThrowAnyException();
    }

    @Test
    void exactly16CharSecret_passes() {
        EscrowStartupValidator v = new EscrowStartupValidator(
                propsWithSecret("1234567890abcdef")); // 16 chars
        assertThatCode(v::validate).doesNotThrowAnyException();
    }
}
