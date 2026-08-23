package com.multind.bitpongo.notification;

import com.multind.bitpongo.common.api.BusinessException;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public final class BarkPushUrlParser {

    private final Set<String> allowedHosts;
    private final boolean allowPrivateHosts;
    private final HostResolver hostResolver;

    public BarkPushUrlParser(BarkProperties properties) {
        this(properties.allowedHosts(), properties.allowPrivateHosts(), InetAddress::getAllByName);
    }

    BarkPushUrlParser(Set<String> allowedHosts, boolean allowPrivateHosts, HostResolver hostResolver) {
        this.allowedHosts = normalizeAllowedHosts(allowedHosts);
        this.allowPrivateHosts = allowPrivateHosts;
        this.hostResolver = Objects.requireNonNull(hostResolver, "hostResolver");
    }

    public BarkTarget parse(String pushUrl) {
        URI uri = parseUri(pushUrl);
        validateShape(uri);

        String host = normalizeHost(uri.getHost());
        String authority = authority(host, uri.getPort());
        if (!allowedHosts.contains(authority)) {
            throw untrustedTarget();
        }
        validateResolvedAddresses(host);

        String deviceKey = firstDecodedPathSegment(uri.getRawPath());
        return new BarkTarget(serverUri(host, uri.getPort()), deviceKey);
    }

    private static URI parseUri(String pushUrl) {
        if (pushUrl == null || pushUrl.isBlank()) {
            throw invalidUrl();
        }
        try {
            return new URI(pushUrl);
        } catch (URISyntaxException exception) {
            throw invalidUrl();
        }
    }

    private static void validateShape(URI uri) {
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || !uri.isAbsolute()
                || uri.getHost() == null
                || uri.getHost().isBlank()
                || uri.getRawUserInfo() != null
                || uri.getRawFragment() != null
                || uri.getPort() < -1) {
            throw invalidUrl();
        }
    }

    private void validateResolvedAddresses(String host) {
        final InetAddress[] addresses;
        try {
            addresses = hostResolver.resolve(host);
        } catch (UnknownHostException exception) {
            throw untrustedTarget();
        }
        if (addresses == null || addresses.length == 0) {
            throw untrustedTarget();
        }
        if (!allowPrivateHosts && Arrays.stream(addresses).anyMatch(BarkPushUrlParser::isNonPublicAddress)) {
            throw untrustedTarget();
        }
    }

    private static String firstDecodedPathSegment(String rawPath) {
        if (rawPath == null) {
            throw invalidUrl();
        }
        for (String rawSegment : rawPath.split("/")) {
            if (rawSegment.isEmpty()) {
                continue;
            }
            final String decoded;
            try {
                decoded = URLDecoder.decode(rawSegment.replace("+", "%2B"), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException exception) {
                throw invalidUrl();
            }
            if (decoded.isBlank() || decoded.codePoints().anyMatch(Character::isISOControl)) {
                throw invalidUrl();
            }
            return decoded;
        }
        throw invalidUrl();
    }

    private static URI serverUri(String host, int port) {
        try {
            return new URI("https", null, host, port, null, null, null);
        } catch (URISyntaxException exception) {
            throw invalidUrl();
        }
    }

    private static String normalizeHost(String host) {
        String unbracketed = host.startsWith("[") && host.endsWith("]")
                ? host.substring(1, host.length() - 1)
                : host;
        return unbracketed.toLowerCase(Locale.ROOT);
    }

    private static String authority(String host, int port) {
        String formattedHost = host.indexOf(':') >= 0 ? "[" + host + "]" : host;
        return port == -1 ? formattedHost : formattedHost + ":" + port;
    }

    private static Set<String> normalizeAllowedHosts(Set<String> allowedHosts) {
        if (allowedHosts == null) {
            return Set.of();
        }
        return allowedHosts.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(value -> value.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    private static boolean isNonPublicAddress(InetAddress address) {
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }

        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int first = Byte.toUnsignedInt(bytes[0]);
            int second = Byte.toUnsignedInt(bytes[1]);
            int third = Byte.toUnsignedInt(bytes[2]);
            return first == 0
                    || first == 10
                    || first == 127
                    || first >= 224
                    || (first == 100 && second >= 64 && second <= 127)
                    || (first == 169 && second == 254)
                    || (first == 172 && second >= 16 && second <= 31)
                    || (first == 192 && second == 0 && third == 0)
                    || (first == 192 && second == 0 && third == 2)
                    || (first == 192 && second == 88 && third == 99)
                    || (first == 192 && second == 168)
                    || (first == 198 && (second == 18 || second == 19))
                    || (first == 198 && second == 51 && third == 100)
                    || (first == 203 && second == 0 && third == 113);
        }

        int first = Byte.toUnsignedInt(bytes[0]);
        int second = Byte.toUnsignedInt(bytes[1]);
        int third = Byte.toUnsignedInt(bytes[2]);
        boolean globallyRoutablePrefix = (first & 0xe0) == 0x20;
        boolean protocolAssignments = first == 0x20 && second == 0x01 && third <= 0x01;
        boolean sixToFour = first == 0x20 && second == 0x02;
        boolean documentation = first == 0x20 && second == 0x01
                && third == 0x0d && Byte.toUnsignedInt(bytes[3]) == 0xb8;
        boolean documentationV2 = first == 0x3f && (second & 0xf0) == 0xf0;
        return !globallyRoutablePrefix
                || protocolAssignments
                || sixToFour
                || documentation
                || documentationV2;
    }

    private static BusinessException invalidUrl() {
        return new BusinessException(400, "Bark Push URL 无效");
    }

    private static BusinessException untrustedTarget() {
        return new BusinessException(400, "Bark Push URL 目标不受信任");
    }

    @FunctionalInterface
    interface HostResolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }
}
