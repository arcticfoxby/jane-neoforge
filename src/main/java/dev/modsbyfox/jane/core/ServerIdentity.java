package dev.modsbyfox.jane.core;

import java.util.Locale;
import java.net.InetSocketAddress;

public final class ServerIdentity {
    private static final int DEFAULT_PORT = 25565;

    private ServerIdentity() { }

    public static String normalize(String rawAddress) {
        if (rawAddress == null) throw new IllegalArgumentException("Missing server address");
        String address = rawAddress.trim();
        if (address.isEmpty() || address.length() > 255 || address.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("Invalid server address");
        }

        String host;
        String port = null;
        if (address.startsWith("[")) {
            int close = address.indexOf(']');
            if (close < 0) throw new IllegalArgumentException("Invalid IPv6 server address");
            host = address.substring(1, close);
            if (!ipv6Literal(host)) throw new IllegalArgumentException("Invalid IPv6 server address");
            host = "[" + host.toLowerCase(Locale.ROOT) + "]";
            if (close + 1 < address.length()) {
                if (address.charAt(close + 1) != ':') throw new IllegalArgumentException("Invalid server port");
                port = address.substring(close + 2);
            }
        } else {
            int firstColon = address.indexOf(':');
            int lastColon = address.lastIndexOf(':');
            if (firstColon != lastColon) {
                if (!ipv6Literal(address)) throw new IllegalArgumentException("Invalid IPv6 server address");
                host = "[" + address.toLowerCase(Locale.ROOT) + "]";
            } else {
                host = firstColon < 0 ? address : address.substring(0, firstColon);
                if (firstColon >= 0) port = address.substring(firstColon + 1);
                if (host.isEmpty() || !host.chars().allMatch(ServerIdentity::hostCharacter)) {
                    throw new IllegalArgumentException("Invalid server host");
                }
                host = host.toLowerCase(Locale.ROOT);
            }
        }
        return host + ":" + parsePort(port);
    }

    public static String id(String address) {
        return Hashing.sha256(normalize(address));
    }

    public static InetSocketAddress providerEndpoint(String capturedAddress, int port) {
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Invalid provider port");
        String normalized = normalize(capturedAddress);
        String host = normalized.substring(0, normalized.lastIndexOf(':'));
        if (host.startsWith("[")) host = host.substring(1, host.length() - 1);
        return new InetSocketAddress(host, port);
    }

    private static int parsePort(String value) {
        if (value == null) return DEFAULT_PORT;
        if (value.isEmpty() || !value.chars().allMatch(Character::isDigit)) {
            throw new IllegalArgumentException("Invalid server port");
        }
        try {
            int port = Integer.parseInt(value);
            if (port > 0 && port <= 65535) return port;
        } catch (NumberFormatException ignored) {
            // The port is outside the valid range.
        }
        throw new IllegalArgumentException("Invalid server port");
    }

    private static boolean hostCharacter(int character) {
        return Character.isLetterOrDigit(character) || character == '.' || character == '-' || character == '_';
    }

    private static boolean ipv6Literal(String value) {
        return value.indexOf(':') >= 0 && !value.isEmpty()
                && value.chars().allMatch(character -> character == ':' || character == '.'
                || character >= '0' && character <= '9'
                || character >= 'a' && character <= 'f'
                || character >= 'A' && character <= 'F');
    }
}
