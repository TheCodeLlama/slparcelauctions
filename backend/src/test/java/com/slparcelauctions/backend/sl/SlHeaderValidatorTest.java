package com.slparcelauctions.backend.sl;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.slparcelauctions.backend.sl.exception.InvalidSlHeadersException;

class SlHeaderValidatorTest {

    private static final UUID TRUSTED = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private SlHeaderValidator validator;

    @BeforeEach
    void setup() {
        validator = new SlHeaderValidator(new SlConfigProperties("Production", Set.of(TRUSTED)));
    }

    @Test
    void happyPath_noThrow() {
        assertThatCode(() -> validator.validate("Production", TRUSTED.toString()))
                .doesNotThrowAnyException();
    }

    @Test
    void wrongShard_throws() {
        assertThatThrownBy(() -> validator.validate("Beta", TRUSTED.toString()))
                .isInstanceOf(InvalidSlHeadersException.class);
    }

    @Test
    void nullShard_throws() {
        assertThatThrownBy(() -> validator.validate(null, TRUSTED.toString()))
                .isInstanceOf(InvalidSlHeadersException.class);
    }

    @Test
    void missingOwnerKey_throws() {
        assertThatThrownBy(() -> validator.validate("Production", null))
                .isInstanceOf(InvalidSlHeadersException.class);
    }

    @Test
    void unparseableOwnerKey_throws() {
        assertThatThrownBy(() -> validator.validate("Production", "not-a-uuid"))
                .isInstanceOf(InvalidSlHeadersException.class);
    }

    @Test
    void untrustedOwnerKey_throws() {
        UUID stranger = UUID.fromString("00000000-0000-0000-0000-000000000999");
        assertThatThrownBy(() -> validator.validate("Production", stranger.toString()))
                .isInstanceOf(InvalidSlHeadersException.class);
    }
}
