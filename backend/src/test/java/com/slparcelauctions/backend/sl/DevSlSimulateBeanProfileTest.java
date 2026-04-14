package com.slparcelauctions.backend.sl;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * Proves {@code @Profile("dev")} gates {@link DevSlSimulateController}: loads the
 * context under the {@code test} profile (not {@code dev}), injects the controller
 * field with {@code required=false}, and asserts it's null.
 *
 * <p>Deliberately does NOT use {@code @ActiveProfiles("prod")} - the prod profile
 * requires {@code JWT_SECRET} and fails fast on empty {@code slpa.sl.trusted-owner-keys},
 * neither of which is available in a unit test environment. The {@code test} profile
 * is sufficient to prove the gate is working: it isn't {@code dev}, so the bean
 * must not be registered.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "auth.cleanup.enabled=false",
        // Satisfy the bean dependencies that would otherwise fail in a non-dev profile.
        "jwt.secret=dGVzdC1zZWNyZXQtdGVzdC1zZWNyZXQtdGVzdC1zZWNyZXQtMTI=",
        "slpa.sl.trusted-owner-keys[0]=00000000-0000-0000-0000-000000000001",
        "spring.datasource.url=jdbc:postgresql://localhost:5432/slpa",
        "spring.datasource.username=slpa",
        "spring.datasource.password=slpa",
        "spring.data.redis.host=localhost",
        "spring.jpa.hibernate.ddl-auto=update",
        // Point the storage layer at the local MinIO container the same way the dev
        // profile does - the StorageStartupValidator refuses to boot without a
        // reachable bucket, and the `test` profile has no storage config of its own.
        "slpa.storage.endpoint-override=http://localhost:9000",
        "slpa.storage.path-style-access=true",
        "slpa.storage.access-key-id=slpa-dev-key",
        "slpa.storage.secret-access-key=slpa-dev-secret"
})
class DevSlSimulateBeanProfileTest {

    @Autowired(required = false)
    DevSlSimulateController controller;

    @Test
    void controllerBeanIsNotRegisteredOutsideDevProfile() {
        assertThat(controller)
                .as("DevSlSimulateController must only be wired under @Profile(\"dev\")")
                .isNull();
    }
}
