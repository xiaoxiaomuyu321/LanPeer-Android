package cn.lanpeer;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

/** QR payload is also a copyable invitation. Only literal RFC1918 IPv4 is accepted. */
public final class Pairing {
    public final String host;
    public final int port;
    public final String token;
    public final String fingerprint;

    public Pairing(String host, int port, String token, String fingerprint) {
        if (!LanAddress.isPrivateV4(host)) throw new IllegalArgumentException("请输入局域网 IPv4 地址");
        if (port < 1024 || port > 65535) throw new IllegalArgumentException("端口应为 1024–65535");
        if (!token.matches("[0-9]{8}|[0-9a-f]{32}")) throw new IllegalArgumentException("连接码格式无效");
        if (!fingerprint.matches("[0-9]{16}|[0-9a-f]{64}")) throw new IllegalArgumentException("校验码格式无效");
        this.host = host; this.port = port; this.token = token; this.fingerprint = fingerprint;
    }

    public static Pairing parse(String text) {
        try {
            URI uri = URI.create(text.trim());
            if (!"lanpeer".equals(uri.getScheme()) || uri.getRawUserInfo() != null
                    || uri.getFragment() != null || (uri.getPath() != null && !uri.getPath().isEmpty())) {
                throw new IllegalArgumentException();
            }
            Map<String, String> query = new HashMap<>();
            for (String part : uri.getRawQuery().split("&")) {
                String[] pair = part.split("=", 2);
                if (pair.length != 2 || query.put(pair[0], pair[1]) != null) throw new IllegalArgumentException();
            }
            if (query.size() != 3 || !"1".equals(query.get("v"))) throw new IllegalArgumentException();
            return new Pairing(uri.getHost(), uri.getPort(), query.get("token"), query.get("fp"));
        } catch (Exception e) {
            throw new IllegalArgumentException("邀请格式无效，请扫描被控设备上的二维码");
        }
    }

    public String invitation() {
        return "lanpeer://" + host + ":" + port + "?v=1&token=" + token + "&fp=" + fingerprint;
    }

    public static String deviceCode(String fingerprint) {
        return String.format(java.util.Locale.ROOT, "%016d", new java.math.BigInteger(fingerprint.substring(0, 16), 16)
                .mod(java.math.BigInteger.TEN.pow(16)));
    }
}
