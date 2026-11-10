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
        String[] parts = trimmed.split("\\.");
        if (parts.length != 4) {
            return Result.INVALID_FORMAT;
        }

        int[] octets = new int[4];
        try {
            for (int i = 0; i < 4; i++) {
                octets[i] = Integer.parseInt(parts[i]);
                if (octets[i] < 0 || octets[i] > 255) {
                    return Result.INVALID_FORMAT;
                }
            }
        } catch (NumberFormatException e) {
            return Result.INVALID_FORMAT;
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
