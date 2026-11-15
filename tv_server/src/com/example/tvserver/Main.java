package com.example.tvserver;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;

public class Main {
    private static final int DEFAULT_PORT = 55555;
    private static final String DEFAULT_TOKEN = "123456";
    private static final int HANDSHAKE_TIMEOUT_MS = 5000;

    public static void main(String[] args) {
        int port = DEFAULT_PORT;
        String token = DEFAULT_TOKEN;

        if (args.length >= 1) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                System.err.println("Invalid port number, using default: " + DEFAULT_PORT);
            }
        }

        if (args.length >= 2) {
            token = args[1].trim();
        }

        System.out.println("=================================================");
        System.out.println(" TV Companion Server v0.1.0");
        System.out.println(" Target: Haier 55P7GT-P (MediaTek MT9676)");
        System.out.println(" CleanYourTV Safe: 0% Idle CPU, Low Memory Mode");
        System.out.println("=================================================");
        System.out.println(" Listening Port : " + port);
        System.out.println(" Security Token : " + token);
        printTvNetworkInterfaces();
        System.out.println("=================================================");
        System.out.println("Ready. Waiting for connection from phone...");

        TokenAuth tokenAuth = new TokenAuth(token);
        InputInjector inputInjector = new InputInjector();

        ServerSocket serverSocket = null;
        try {
            serverSocket = new ServerSocket(port);
            serverSocket.setReuseAddress(true);

            final ServerSocket finalServerSocket = serverSocket;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("\n[TvServer] Shutting down server...");
                try {
                    if (finalServerSocket != null && !finalServerSocket.isClosed()) {
                        finalServerSocket.close();
                    }
                } catch (Exception ignored) {
                }
            }));

            while (!serverSocket.isClosed()) {
                Socket clientSocket = null;
                try {
                    clientSocket = serverSocket.accept();
                    clientSocket.setTcpNoDelay(true);
                    clientSocket.setKeepAlive(true);

                    System.out.println("\n[TvServer] Incoming connection from: " + clientSocket.getRemoteSocketAddress());

                    // Validate private LAN subnet
                    if (!tokenAuth.isAllowedClient(clientSocket)) {
                        System.err.println("[TvServer] Rejected non-LAN connection from " + clientSocket.getRemoteSocketAddress());
                        clientSocket.close();
                        continue;
                    }

                    handleClient(clientSocket, tokenAuth, inputInjector);
                } catch (Exception e) {
                    if (!serverSocket.isClosed()) {
                        System.err.println("[TvServer] Connection handler error: " + e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[TvServer] Fatal error opening server socket: " + e.getMessage());
        } finally {
            if (serverSocket != null) {
                try {
                    serverSocket.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static void handleClient(Socket socket, TokenAuth tokenAuth, InputInjector inputInjector) {
        ScreenStreamer streamer = null;
        try {
            socket.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            OutputStream out = socket.getOutputStream();

            // 1. Read handshake line from client (bounded by handshake SoTimeout)
            String handshakeLine = reader.readLine();
            if (handshakeLine == null || handshakeLine.trim().isEmpty()) {
                System.err.println("[TvServer] Client disconnected before handshake.");
                closeQuietly(socket);
                return;
            }
            socket.setSoTimeout(0);

            // Parse token and profile from JSON
            String clientToken = extractJsonField(handshakeLine, "token");
            String clientProfile = extractJsonField(handshakeLine, "profile");

            if (!tokenAuth.verifyToken(clientToken)) {
                System.err.println("[TvServer] Token authentication failed. Given: '" + clientToken + "'");
                String rejectJson = "{\"ok\":false,\"error\":\"Invalid security token\",\"token_ok\":false}\n";
                out.write(rejectJson.getBytes(StandardCharsets.UTF_8));
                out.flush();
                closeQuietly(socket);
                return;
            }

            // 2. Initialize hardware screen streamer; close socket immediately if screenrecord exits
            streamer = new ScreenStreamer(out, clientProfile, () -> closeQuietly(socket));
            int width = streamer.getWidth();
            int height = streamer.getHeight();
            int fps = streamer.getFps();

            // 3. Send handshake response
            String acceptJson = String.format(
                    java.util.Locale.US,
                    "{\"ok\":true,\"width\":%d,\"height\":%d,\"fps\":%d,\"refresh_hz\":60,\"token_ok\":true}\n",
                    width, height, fps
            );
            out.write(acceptJson.getBytes(StandardCharsets.UTF_8));
            out.flush();
            System.out.println("[TvServer] Handshake accepted! Streaming " + width + "x" + height + "@" + fps + "fps to phone.");

            // 4. Start video streaming thread
            streamer.start();

            // 5. Command listening loop
            String line;
            while (!socket.isClosed() && (line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) continue;

                String type = extractJsonField(line, "t");
                if ("key".equals(type)) {
                    String codeStr = extractJsonField(line, "code");
                    try {
                        int code = Integer.parseInt(codeStr);
                        inputInjector.injectKey(code);
                    } catch (NumberFormatException ignored) {
                    }
                } else if ("tap".equals(type)) {
                    inputInjector.injectTap();
                } else if ("swipe".equals(type)) {
                    String dir = extractJsonField(line, "dir");
                    inputInjector.injectSwipe(dir);
                } else if ("text".equals(type)) {
                    String val = extractJsonField(line, "value");
                    inputInjector.injectText(val);
                }
            }
        } catch (Exception e) {
            System.out.println("[TvServer] Client connection ended: " + e.getMessage());
        } finally {
            if (streamer != null) {
                streamer.stop();
            }
            closeQuietly(socket);
            System.out.println("[TvServer] Client disconnected. Server is idle (0% CPU).");
        }
    }

    private static void closeQuietly(Socket socket) {
        if (socket == null) return;
        try {
            if (!socket.isClosed()) {
                socket.close();
            }
        } catch (Exception ignored) {
        }
    }

    private static String extractJsonField(String json, String field) {
        if (json == null || field == null) return "";
        int len = json.length();
        int i = skipWs(json, 0);
        if (i >= len || json.charAt(i) != '{') return "";
        i++;

        StringBuilder buf = new StringBuilder();
        while (i < len) {
            i = skipWs(json, i);
            if (i >= len || json.charAt(i) == '}') break;
            if (json.charAt(i) == ',') {
                i++;
                continue;
            }
            if (json.charAt(i) != '"') break;

            buf.setLength(0);
            i = readJsonString(json, i, buf);
            if (i == -1) break;
            String key = buf.toString();

            i = skipWs(json, i);
            if (i >= len || json.charAt(i) != ':') break;
            i = skipWs(json, i + 1);
            if (i >= len) break;

            if (json.charAt(i) == '"') {
                buf.setLength(0);
                i = readJsonString(json, i, buf);
                if (i == -1) break;
                if (field.equals(key)) {
                    return buf.toString().trim();
                }
            } else if (json.charAt(i) == '{' || json.charAt(i) == '[') {
                int endCompound = skipJsonCompound(json, i);
                if (endCompound == -1) break;
                if (field.equals(key)) {
                    return json.substring(i, endCompound).trim();
                }
                i = endCompound;
            } else {
                int start = i;
                while (i < len) {
                    char c = json.charAt(i);
                    if (c == ',' || c == '}' || c <= ' ') break;
                    i++;
                }
                if (field.equals(key)) {
                    return json.substring(start, i).trim();
                }
            }
        }
        return "";
    }

    private static int skipWs(String s, int i) {
        int len = s.length();
        while (i < len && s.charAt(i) <= ' ') {
            i++;
        }
        return i;
    }

    private static int readJsonString(String s, int startQuoteIdx, StringBuilder out) {
        int len = s.length();
        int i = startQuoteIdx + 1;
        while (i < len) {
            char c = s.charAt(i++);
            if (c == '"') {
                return i;
            }
            if (c == '\\' && i < len) {
                char esc = s.charAt(i++);
                switch (esc) {
                    case '"': out.append('"'); break;
                    case '\\': out.append('\\'); break;
                    case '/': out.append('/'); break;
                    case 'b': out.append('\b'); break;
                    case 'f': out.append('\f'); break;
                    case 'n': out.append('\n'); break;
                    case 'r': out.append('\r'); break;
                    case 't': out.append('\t'); break;
                    case 'u':
                        if (i + 4 <= len) {
                            try {
                                out.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                                i += 4;
                            } catch (NumberFormatException e) {
                                out.append("\\u");
                            }
                        } else {
                            out.append("\\u");
                        }
                        break;
                    default:
                        out.append(esc);
                        break;
                }
            } else {
                out.append(c);
            }
        }
        return -1;
    }

    private static int skipJsonCompound(String s, int startIdx) {
        int len = s.length();
        int depth = 0;
        boolean inString = false;
        for (int i = startIdx; i < len; i++) {
            char c = s.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inString = false;
                }
            } else {
                if (c == '"') {
                    inString = true;
                } else if (c == '{' || c == '[') {
                    depth++;
                } else if (c == '}' || c == ']') {
                    depth--;
                    if (depth == 0) {
                        return i + 1;
                    }
                }
            }
        }
        return -1;
    }

    private static void printTvNetworkInterfaces() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            System.out.println(" TV IP Addresses (Use on Phone):");
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (ni.isLoopback() || !ni.isUp()) continue;

                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (addr instanceof Inet4Address) {
                        System.out.println("   • " + ni.getName() + " -> " + addr.getHostAddress());
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }
}
