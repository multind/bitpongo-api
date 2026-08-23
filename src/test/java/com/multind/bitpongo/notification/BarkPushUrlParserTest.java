package com.multind.bitpongo.notification;

import com.multind.bitpongo.common.api.BusinessException;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BarkPushUrlParserTest {

    private static final InetAddress PUBLIC_ADDRESS = address("203.0.114.10");

    @Test
    void extractsOnlyServerAndDecodedFirstPathSegmentFromCopiedTestUrl() {
        BarkTarget target = parser(Set.of("api.day.app"), false, PUBLIC_ADDRESS)
                .parse("https://api.day.app/device%2Dkey/sample-title?call=1&sound=alarm");

        assertThat(target.serverUrl()).isEqualTo(URI.create("https://api.day.app"));
        assertThat(target.deviceKey()).isEqualTo("device-key");
    }

    @Test
    void usesFirstNonEmptyPathSegmentAsDeviceKey() {
        BarkTarget target = parser(Set.of("api.day.app"), false, PUBLIC_ADDRESS)
                .parse("https://api.day.app//fake-device-key/ignored-title");

        assertThat(target.deviceKey()).isEqualTo("fake-device-key");
    }

    @Test
    void requiresExactHostAndPortAllowlistMatch() {
        BarkTarget target = parser(Set.of("push.example.test:8443"), false, PUBLIC_ADDRESS)
                .parse("https://push.example.test:8443/fake-device-key");

        assertThat(target.serverUrl()).isEqualTo(URI.create("https://push.example.test:8443"));
        assertThatThrownBy(() -> parser(Set.of("push.example.test"), false, PUBLIC_ADDRESS)
                .parse("https://push.example.test:8443/fake-device-key"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不受信任");
    }

    @Test
    void rejectsPrivateLiteralEvenWhenAllowlisted() {
        assertThatThrownBy(() -> new BarkPushUrlParser(
                Set.of("127.0.0.1"), false, ignored -> new InetAddress[]{address("127.0.0.1")})
                .parse("https://127.0.0.1/fake-device-key"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不受信任");
    }

    @Test
    void rejectsTrustedHostnameWhenAnyResolvedAddressIsPrivate() {
        BarkPushUrlParser parser = new BarkPushUrlParser(
                Set.of("push.example.test"), false,
                ignored -> new InetAddress[]{PUBLIC_ADDRESS, address("10.0.0.8")});

        assertThatThrownBy(() -> parser.parse("https://push.example.test/fake-device-key"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不受信任");
    }

    @Test
    void permitsTrustedPrivateTargetOnlyWhenExplicitlyEnabled() {
        BarkTarget target = new BarkPushUrlParser(
                Set.of("127.0.0.1:8443"), true,
                ignored -> new InetAddress[]{address("127.0.0.1")})
                .parse("https://127.0.0.1:8443/fake-device-key");

        assertThat(target.serverUrl()).isEqualTo(URI.create("https://127.0.0.1:8443"));
    }

    @Test
    void rejectsUnsafeOrIncompletePushUrls() {
        BarkPushUrlParser parser = parser(Set.of("api.day.app"), false, PUBLIC_ADDRESS);

        assertThatThrownBy(() -> parser.parse("http://api.day.app/fake-device-key"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> parser.parse("https://user@api.day.app/fake-device-key"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> parser.parse("https://api.day.app/fake-device-key#fragment"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> parser.parse("https://api.day.app/"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void rejectsUnresolvableTrustedHost() {
        BarkPushUrlParser parser = new BarkPushUrlParser(
                Set.of("push.example.test"), false,
                ignored -> { throw new UnknownHostException("synthetic DNS failure"); });

        assertThatThrownBy(() -> parser.parse("https://push.example.test/fake-device-key"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不受信任");
    }

    private static BarkPushUrlParser parser(
            Set<String> allowedHosts, boolean allowPrivateHosts, InetAddress address) {
        return new BarkPushUrlParser(allowedHosts, allowPrivateHosts,
                ignored -> new InetAddress[]{address});
    }

    private static InetAddress address(String literal) {
        try {
            return InetAddress.getByName(literal);
        } catch (UnknownHostException exception) {
            throw new IllegalArgumentException(exception);
        }
    }
}
