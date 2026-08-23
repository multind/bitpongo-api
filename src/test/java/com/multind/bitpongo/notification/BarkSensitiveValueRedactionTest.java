package com.multind.bitpongo.notification;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class BarkSensitiveValueRedactionTest {

    @Test
    void propertiesStringRepresentationDoesNotExposeConfiguredSecrets() {
        BarkProperties properties = new BarkProperties(
                true,
                "sensitive-admin-marker",
                Set.of("api.day.app"),
                false,
                "sensitive-encryption-key-marker",
                true,
                false,
                "redacted-public-origin");

        assertThat(properties.toString())
                .doesNotContain("sensitive-admin-marker")
                .doesNotContain("sensitive-encryption-key-marker");
    }

    @Test
    void targetStringRepresentationDoesNotExposeDeviceKey() {
        BarkTarget target = new BarkTarget(
                URI.create("https://api.day.app"), "sensitive-device-marker");

        assertThat(target.toString()).doesNotContain("sensitive-device-marker");
    }
}
