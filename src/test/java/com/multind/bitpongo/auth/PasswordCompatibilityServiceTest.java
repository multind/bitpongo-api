package com.multind.bitpongo.auth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordCompatibilityServiceTest {

    private final PasswordCompatibilityService service = new PasswordCompatibilityService();

    @Test
    void verifiesHashProducedByPython() {
        String stored = "0123456789abcdef0123456789abcdef"
                + "9963c21abea5fad794d0b8ae206339a6c48de6ef46c9c9e5213c49679b50c5f6";

        assertThat(service.matches("correct-horse", stored)).isTrue();
        assertThat(service.matches("wrong", stored)).isFalse();
    }

    @Test
    void writesSameNinetySixCharacterFormat() {
        String stored = service.hash("new-password");

        assertThat(stored).hasSize(96).matches("[0-9a-f]{96}");
        assertThat(service.matches("new-password", stored)).isTrue();
    }
}
