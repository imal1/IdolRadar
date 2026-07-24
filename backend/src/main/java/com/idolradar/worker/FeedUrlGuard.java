package com.idolradar.worker;

import java.net.IDN;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Locale;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 在发起网络请求前校验外部 RSS 目标，防止 SSRF。
 *
 * <p>仅允许 HTTPS 与公网主机/DNS 结果；任一 DNS 地址不安全即拒绝整个目标。
 * 返回的地址集合供 downloader 在连接阶段进行 DNS pin。
 */
@Component
@ConditionalOnProperty(name = "app.mode", havingValue = "worker")
public class FeedUrlGuard {
    @FunctionalInterface
    public interface HostResolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    /** 已校验 URI 与用于连接时 DNS pin 的防御性地址副本。 */
    public record ValidatedTarget(URI uri, InetAddress[] addresses) {
        public ValidatedTarget {
            addresses = addresses.clone();
        }

        @Override
        public InetAddress[] addresses() {
            return addresses.clone();
        }
    }

    private final HostResolver resolver;

    public FeedUrlGuard() {
        this(InetAddress::getAllByName);
    }

    FeedUrlGuard(HostResolver resolver) {
        this.resolver = resolver;
    }

    public ValidatedTarget validateAndResolve(String rawUrl) {
        URI uri = validateUrl(rawUrl);
        InetAddress[] addresses;
        try {
            addresses = resolver.resolve(uri.getHost());
        } catch (UnknownHostException error) {
            throw new FeedException("DNS_LOOKUP_FAILED", "RSS 域名解析失败", error);
        }
        if (addresses == null || addresses.length == 0) {
            throw new FeedException("DNS_LOOKUP_FAILED", "RSS 域名解析失败");
        }
        if (Arrays.stream(addresses).anyMatch(FeedUrlGuard::isUnsafeAddress)) {
            throw new FeedException("UNSAFE_FEED_URL", "RSS URL 不能指向私有网络");
        }
        return new ValidatedTarget(uri, addresses);
    }

    public URI validateUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank() || rawUrl.length() > 2048) {
            throw new FeedException("INVALID_FEED_URL", "RSS URL 无效");
        }

        URI parsed;
        try {
            parsed = URI.create(rawUrl.trim());
        } catch (IllegalArgumentException error) {
            throw new FeedException("INVALID_FEED_URL", "RSS URL 无效", error);
        }
        if (!"https".equalsIgnoreCase(parsed.getScheme())
                || parsed.getHost() == null
                || parsed.getRawUserInfo() != null) {
            throw new FeedException("INVALID_FEED_URL", "RSS URL 必须使用 HTTPS");
        }

        String host;
        try {
            host = normalizeHost(parsed.getHost());
        } catch (IllegalArgumentException error) {
            throw new FeedException("INVALID_FEED_URL", "RSS URL 无效", error);
        }
        if (isUnsafeHostname(host)) {
            throw new FeedException("UNSAFE_FEED_URL", "RSS URL 不能指向私有网络");
        }
        try {
            String authority = host.contains(":") ? "[" + host + "]" : host;
            if (parsed.getPort() >= 0) authority += ":" + parsed.getPort();
            String path = parsed.getRawPath();
            String query = parsed.getRawQuery() == null ? "" : "?" + parsed.getRawQuery();
            return new URI("https://" + authority + (path == null || path.isEmpty() ? "/" : path) + query);
        } catch (URISyntaxException error) {
            throw new FeedException("INVALID_FEED_URL", "RSS URL 无效", error);
        }
    }

    static String normalizeHost(String host) {
        String normalized = host == null ? "" : host.trim().toLowerCase(Locale.ROOT);
        if (normalized.endsWith(".")) normalized = normalized.substring(0, normalized.length() - 1);
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        return normalized.contains(":") ? normalized : IDN.toASCII(normalized);
    }

    static boolean isUnsafeHostname(String host) {
        String value = normalizeHost(host);
        if (value.isBlank()) return true;
        if (isIpLiteral(value)) {
            try {
                return isUnsafeAddress(InetAddress.getByName(value));
            } catch (UnknownHostException error) {
                return true;
            }
        }
        return !value.contains(".")
                || value.equals("localhost")
                || value.equals("metadata.google.internal")
                || value.endsWith(".localhost")
                || value.endsWith(".local")
                || value.endsWith(".internal")
                || value.endsWith(".home")
                || value.endsWith(".lan");
    }

    static boolean isUnsafeAddress(InetAddress address) {
        if (address == null
                || address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) return isUnsafeIpv4(bytes);
        if (bytes.length == 16) return isUnsafeIpv6(bytes);
        return true;
    }

    private static boolean isUnsafeIpv4(byte[] raw) {
        int a = Byte.toUnsignedInt(raw[0]);
        int b = Byte.toUnsignedInt(raw[1]);
        int c = Byte.toUnsignedInt(raw[2]);
        return a == 0
                || a == 10
                || a == 127
                || (a == 100 && b >= 64 && b <= 127)
                || (a == 169 && b == 254)
                || (a == 172 && b >= 16 && b <= 31)
                || (a == 192 && b == 0 && c == 0)
                || (a == 192 && b == 0 && c == 2)
                || (a == 192 && b == 88 && c == 99)
                || (a == 192 && b == 168)
                || (a == 198 && (b == 18 || b == 19))
                || (a == 198 && b == 51 && c == 100)
                || (a == 203 && b == 0 && c == 113)
                || a >= 224;
    }

    private static boolean isUnsafeIpv6(byte[] bytes) {
        boolean mappedIpv4 = true;
        for (int index = 0; index < 10; index++) mappedIpv4 &= bytes[index] == 0;
        mappedIpv4 &= Byte.toUnsignedInt(bytes[10]) == 0xff && Byte.toUnsignedInt(bytes[11]) == 0xff;
        if (mappedIpv4) return isUnsafeIpv4(Arrays.copyOfRange(bytes, 12, 16));

        int first = Byte.toUnsignedInt(bytes[0]);
        int second = Byte.toUnsignedInt(bytes[1]);
        int third = Byte.toUnsignedInt(bytes[2]);
        int fourth = Byte.toUnsignedInt(bytes[3]);
        if ((first & 0xe0) != 0x20) return true; // 仅 2000::/3 为全局可路由范围。
        if (first == 0x20 && second == 0x01 && third == 0x0d && fourth == 0xb8) return true;
        if (first == 0x20 && second == 0x01 && third == 0x00
                && (fourth == 0x02 || fourth == 0x10 || fourth == 0x20)) return true;
        return first == 0x20 && second == 0x02; // 拒绝 6to4。
    }

    private static boolean isIpLiteral(String host) {
        return host.indexOf(':') >= 0 || host.matches("[0-9.]+");
    }
}
