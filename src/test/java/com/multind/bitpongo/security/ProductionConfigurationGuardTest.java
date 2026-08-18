package com.multind.bitpongo.security;

import com.multind.bitpongo.infrastructure.ProductionConfigurationGuard;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductionConfigurationGuardTest {
    @Test
    void rejectsPublishedExampleSecrets() {
        assertThatThrownBy(() -> new ProductionConfigurationGuard(
                "zhitoubao", "replace-with-local-database-password",
                "replace-with-at-least-32-random-characters"))
                .hasMessageContaining("示例值");
    }
}
