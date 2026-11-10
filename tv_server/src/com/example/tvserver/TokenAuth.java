package com.example.tvserver;

import java.net.InetAddress;
import java.net.Socket;

public class TokenAuth {
    private final String expectedToken;

    public TokenAuth(String expectedToken) {
        this.expectedToken = (expectedToken == null || expectedToken.trim().isEmpty()) ? "123456" : expectedToken.trim();
    }

    public String getExpectedToken() {
        return expectedToken;
    }

    public boolean verifyToken(String clientToken) {
        if (clientToken == null) return false;
        return expectedToken.equals(clientToken.trim());
    }

    /**
     * Rejects connections that do not originate from allowed private IPv4 LAN addresses.
     */
    public boolean isAllowedClient(Socket socket) {
        if (socket == null) return false;
        InetAddress address = socket.getInetAddress();
        if (address == null) return false;

        if (address.isLoopbackAddress()) {
            return true;
        }

        byte[] raw = address.getAddress();
        if (raw.length != 4) {
            // Reject non-IPv4 for simple deterministic LAN security
            return false;
        }

        int octet1 = raw[0] & 0xFF;
        int octet2 = raw[1] & 0xFF;

        // 10.0.0.0/8
        if (octet1 == 10) return true;

        // 172.16.0.0/12
        if (octet1 == 172 && (octet2 >= 16 && octet2 <= 31)) return true;

        // 192.168.0.0/16
        if (octet1 == 192 && octet2 == 168) return true;

        // 169.254.0.0/16 (Link-local / Hotspot)
        if (octet1 == 169 && octet2 == 254) return true;

        return false;
    }
}
