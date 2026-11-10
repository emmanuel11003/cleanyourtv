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
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            OutputStream out = socket.getOutputStream();

            // 1. Read handshake line from client
            String handshakeLine = reader.readLine();
            if (handshakeLine == null || handshakeLine.trim().isEmpty()) {
                System.err.println("[TvServer] Client disconnected before handshake.");
                socket.close();
                return;
            }

            // Parse token and profile from JSON (simple zero-dependency string parsing)
            String clientToken = extractJsonField(handshakeLine, "token");
            String clientProfile = extractJsonField(handshakeLine, "profile");

            if (!tokenAuth.verifyToken(clientToken)) {
                System.err.println("[TvServer] Token authentication failed. Given: '" + clientToken + "'");
                String rejectJson = "{\"ok\":false,\"error\":\"Invalid security token\",\"token_ok\":false}\n";
                out.write(rejectJson.getBytes(StandardCharsets.UTF_8));
                out.flush();
                socket.close();
                return;
            }

            // 2. Initialize hardware screen streamer
            streamer = new ScreenStreamer(out, clientProfile);
            int width = streamer.getWidth();
            int height = streamer.getHeight();
            int fps = streamer.getFps();

            // 3. Send handshake response
            String acceptJson = String.format(
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
            while ((line = reader.readLine()) != null) {
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
            try {
                socket.close();
            } catch (Exception ignored) {
            }
            System.out.println("[TvServer] Client disconnected. Server is idle (0% CPU).");
        }
    }

    private static String extractJsonField(String json, String field) {
        String keyPattern = "\"" + field + "\":";
        int idx = json.indexOf(keyPattern);
        if (idx == -1) return "";

        int valStart = idx + keyPattern.length();
        while (valStart < json.length() && (json.charAt(valStart) == ' ' || json.charAt(valStart) == '"')) {
            valStart++;
        }

        int valEnd = valStart;
        while (valEnd < json.length() && json.charAt(valEnd) != '"' && json.charAt(valEnd) != ',' && json.charAt(valEnd) != '}') {
            valEnd++;
        }

        return json.substring(valStart, valEnd).trim();
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
