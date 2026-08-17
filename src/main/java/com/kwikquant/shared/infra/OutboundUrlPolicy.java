package com.kwikquant.shared.infra;

import java.net.IDN;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;

/** Security policy for user-controlled outbound HTTP destinations. */
public final class OutboundUrlPolicy {

    private OutboundUrlPolicy() {}

    public static URI validateAndNormalizeBaseUrl(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("baseUrl is required");
        }

        URI uri;
        try {
            uri = new URI(value.trim());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("baseUrl must be a valid URI", e);
        }
        if (!uri.isAbsolute() || !"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("baseUrl must use HTTPS");
        }
        if (uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("baseUrl must not contain userinfo, query, or fragment");
        }
        if (uri.getPort() < -1 || uri.getPort() > 65535) {
            throw new IllegalArgumentException("baseUrl has an invalid port");
        }

        String rawHost = uri.getHost();
        if (rawHost == null || rawHost.isBlank() || rawHost.indexOf('%') >= 0) {
            throw new IllegalArgumentException("baseUrl must contain a valid host");
        }
        String host = rawHost;
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        host = host.endsWith(".") ? host.substring(0, host.length() - 1) : host;
        if (host.equalsIgnoreCase("localhost")) {
            throw new IllegalArgumentException("baseUrl host must be publicly routable");
        }

        String asciiHost;
        if (host.indexOf(':') >= 0) {
            asciiHost = host.toLowerCase(Locale.ROOT);
            validateLiteralAddress(asciiHost);
        } else {
            try {
                asciiHost = IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("baseUrl must contain a valid host", e);
            }
            if (asciiHost.chars().allMatch(c -> Character.isDigit(c) || c == '.')) {
                validateStrictIpv4Literal(asciiHost);
            }
        }

        String path = uri.getRawPath();
        if (path == null || path.isEmpty() || "/".equals(path)) {
            path = "";
        } else {
            while (path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }
        }
        try {
            return new URI("https", null, asciiHost, uri.getPort() == 443 ? -1 : uri.getPort(), path, null, null);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("baseUrl must be a valid URI", e);
        }
    }

    public static void validateResolvedAddresses(List<InetAddress> addresses) {
        if (addresses == null || addresses.isEmpty()) {
            throw new IllegalArgumentException("baseUrl host did not resolve");
        }
        if (addresses.stream().anyMatch(address -> !isPublicUnicast(address))) {
            throw new IllegalArgumentException("baseUrl host must resolve only to public addresses");
        }
    }

    static boolean isPublicUnicast(InetAddress address) {
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address) {
            int a = Byte.toUnsignedInt(bytes[0]);
            int b = Byte.toUnsignedInt(bytes[1]);
            return a != 0
                    && a != 10
                    && a != 127
                    && !(a == 100 && b >= 64 && b <= 127)
                    && !(a == 169 && b == 254)
                    && !(a == 172 && b >= 16 && b <= 31)
                    && !(a == 192 && b == 0)
                    && !(a == 192 && b == 168)
                    && !(a == 198 && (b == 18 || b == 19))
                    && !(a == 198 && b == 51)
                    && !(a == 203 && b == 0)
                    && a < 224;
        }
        if (address instanceof Inet6Address) {
            int first = Byte.toUnsignedInt(bytes[0]);
            boolean globalUnicast = (first & 0xe0) == 0x20;
            boolean documentation = first == 0x20
                    && Byte.toUnsignedInt(bytes[1]) == 0x01
                    && Byte.toUnsignedInt(bytes[2]) == 0x0d
                    && Byte.toUnsignedInt(bytes[3]) == 0xb8;
            boolean teredo = first == 0x20 && Byte.toUnsignedInt(bytes[1]) == 0x01 && bytes[2] == 0 && bytes[3] == 0;
            boolean sixToFour = first == 0x20 && Byte.toUnsignedInt(bytes[1]) == 0x02;
            return globalUnicast && !documentation && !teredo && !sixToFour;
        }
        return false;
    }

    private static void validateLiteralAddress(String host) {
        try {
            InetAddress address = InetAddress.getByName(host);
            validateResolvedAddresses(List.of(address));
        } catch (Exception e) {
            throw new IllegalArgumentException("baseUrl must contain a public IP address", e);
        }
    }

    private static void validateStrictIpv4Literal(String host) {
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) {
            throw new IllegalArgumentException("baseUrl must use canonical IPv4 notation");
        }
        for (String part : parts) {
            if (part.isEmpty() || (part.length() > 1 && part.startsWith("0"))) {
                throw new IllegalArgumentException("baseUrl must use canonical IPv4 notation");
            }
            try {
                if (Integer.parseInt(part) > 255) {
                    throw new IllegalArgumentException("baseUrl must use canonical IPv4 notation");
                }
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("baseUrl must use canonical IPv4 notation", e);
            }
        }
        validateLiteralAddress(host);
    }
}
