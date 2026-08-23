package com.multind.bitpongo.notification;

import com.multind.bitpongo.common.api.BusinessException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BarkPushUrlParserSpecialAddressTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "64:ff9b::7f00:1",
            "2002:7f00:1::",
            "2001:10::1"
    })
    void rejectsSpecialPurposeIpv6TargetsEvenWhenHostnameIsAllowlisted(String addressLiteral) {
        BarkPushUrlParser parser = new BarkPushUrlParser(
                Set.of("push.example.test"), false,
                ignored -> new InetAddress[]{InetAddress.getByName(addressLiteral)});

        assertThatThrownBy(() -> parser.parse("https://push.example.test/fake-device-key"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不受信任");
    }
}
