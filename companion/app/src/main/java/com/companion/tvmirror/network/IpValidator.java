package com.companion.tvmirror.network;

public class IpValidator {

    public enum Result {
        VALID_PRIVATE_IP,
        INVALID_FORMAT,
        PUBLIC_IP_REJECTED
    }

    /**
     * Validates whether an input IP address belongs to allowed private LAN / hotspot subnets.
     * Allowed:
     * - 10.0.0.0/8
     * - 172.16.0.0/12 (172.16.x.x to 172.31.x.x)
     * - 192.168.0.0/16
     * - 169.254.0.0/16 (Link-local / Phone Hotspot)
     * - 127.0.0.1 (Localhost / ADB reverse)
     */
    public static Result validate(String ip) {
        if (ip == null || ip.trim().isEmpty()) {
            return Result.INVALID_FORMAT;
        }

        String trimmed = ip.trim();
        String[] parts = trimmed.split("\\.", -1);
        if (parts.length != 4) {
            return Result.INVALID_FORMAT;
        }

        int[] octets = new int[4];
        for (int i = 0; i < 4; i++) {
            String part = parts[i];
            if (part.isEmpty() || part.length() > 3) {
                return Result.INVALID_FORMAT;
            }
            if (part.length() > 1 && part.charAt(0) == '0') {
                return Result.INVALID_FORMAT;
            }
            int value = 0;
            for (int j = 0; j < part.length(); j++) {
                char c = part.charAt(j);
                if (c < '0' || c > '9') {
                    return Result.INVALID_FORMAT;
                }
                value = value * 10 + (c - '0');
            }
            if (value > 255) {
                return Result.INVALID_FORMAT;
            }
            octets[i] = value;
        }

        // Localhost
        if (octets[0] == 127) {
            return Result.VALID_PRIVATE_IP;
        }

        // 10.0.0.0/8
        if (octets[0] == 10) {
            return Result.VALID_PRIVATE_IP;
        }

        // 172.16.0.0/12 (172.16.x.x to 172.31.x.x)
        if (octets[0] == 172 && (octets[1] >= 16 && octets[1] <= 31)) {
            return Result.VALID_PRIVATE_IP;
        }

        // 192.168.0.0/16
        if (octets[0] == 192 && octets[1] == 168) {
            return Result.VALID_PRIVATE_IP;
        }

        // 169.254.0.0/16 (Link-local / Hotspot)
        if (octets[0] == 169 && octets[1] == 254) {
            return Result.VALID_PRIVATE_IP;
        }

        // Any other IP is public WAN and must be rejected for offline LAN privacy
        return Result.PUBLIC_IP_REJECTED;
    }
}
