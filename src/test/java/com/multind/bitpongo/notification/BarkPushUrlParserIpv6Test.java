package com.multind.bitpongo.notification;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class BarkPushUrlParserIpv6Test {

    @Test
    void matchesBracketedIpv6AuthorityWithoutChangingTheServerOrigin() throws UnknownHostException {
        InetAddress loopback = InetAddress.getByName("::1");
        BarkPushUrlParser parser = new BarkPushUrlParser(
                Set.of("[::1]:8443"), true, ignored -> new InetAddress[]{loopback});

        BarkTarget target = parser.parse("https://[::1]:8443/fake-device-key");

        assertThat(target.serverUrl()).isEqualTo(URI.create("https://[::1]:8443"));
    }
}
