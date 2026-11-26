package com.gathin.tvhome;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Pure-Java local Wi-Fi HTTP/1.1 server consolidated from addons/wallpaper-sync,
 * extended with PIN-protected JSON Settings Backup & Restore endpoints.
 */
final class WallpaperHttpServer {
    interface Handler {
        String statusJson();
        Result applyWallpaper(String target, byte[] imageBytes);
        Result clearWallpaper(String target);
        String exportBackupJson();
        Result importBackupJson(String json);
    }

    static final class Result {
        final boolean ok;
        final int httpCode;
        final String error;

        Result(boolean ok, int httpCode, String error) {
            this.ok = ok;
            this.httpCode = httpCode;
            this.error = error;
        }

        static Result success() {
            return new Result(true, 200, null);
        }

        static Result fail(int code, String error) {
            return new Result(false, code, error);
        }
    }

    private final Handler handler;
    private volatile String pin;
    private ServerSocket serverSocket;
    private ExecutorService pool;
    private volatile boolean running;
    private int port;

    WallpaperHttpServer(Handler handler, String pin) {
        this.handler = handler;
        this.pin = pin;
    }

    void setPin(String pin) {
        this.pin = pin;
    }

    int getPort() {
        return port;
    }

    boolean isRunning() {
        return running;
    }

    synchronized int start(int preferredPort) throws IOException {
        if (running) return port;
        IOException last = null;
        for (int p = preferredPort; p < preferredPort + 10; p++) {
            try {
                ServerSocket ss = new ServerSocket();
                ss.setReuseAddress(true);
                ss.bind(new InetSocketAddress(p));
                serverSocket = ss;
                port = p;
                break;
            } catch (IOException e) {
                last = e;
            }
        }
        if (serverSocket == null) {
            throw last != null ? last : new IOException("No free port");
        }
        running = true;
        ThreadPoolExecutor exec = new ThreadPoolExecutor(0, 4, 30L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(16), r -> {
            Thread t = new Thread(r, "tvhome-sync-io");
            t.setDaemon(true);
            return t;
        });
        exec.allowCoreThreadTimeOut(true);
        pool = exec;
        Thread acceptThread = new Thread(() -> {
            while (running) {
                try {
                    Socket client = serverSocket.accept();
                    client.setSoTimeout(30_000);
                    try {
                        pool.execute(() -> handleClient(client));
                    } catch (RuntimeException rejected) {
                        try {
                            client.close();
                        } catch (IOException ignored) {
                        }
                    }
                } catch (IOException e) {
                    if (running) break;
                }
            }
        }, "tvhome-sync-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
        return port;
    }

    synchronized void stop() {
        running = false;
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
            }
            serverSocket = null;
        }
        if (pool != null) {
            pool.shutdownNow();
            pool = null;
        }
    }

    private void handleClient(Socket socket) {
        try (Socket s = socket;
             BufferedInputStream in = new BufferedInputStream(s.getInputStream());
             OutputStream out = s.getOutputStream()) {
            String requestLine = readLine(in);
            if (requestLine == null || requestLine.isEmpty()) return;

            String[] parts = requestLine.split(" ");
            if (parts.length < 2) {
                writeJson(out, 400, "{\"ok\":false,\"error\":\"Bad request\"}");
                return;
            }
            String method = parts[0].toUpperCase(Locale.ROOT);
            String rawPath = parts[1];

            Map<String, String> headers = new HashMap<>();
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon > 0) {
                    headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT),
                            line.substring(colon + 1).trim());
                }
            }

            dispatch(method, rawPath, headers, in, out);
        } catch (IOException ignored) {
        }
    }

    void dispatch(String method, String rawPath, Map<String, String> headers, InputStream body, OutputStream out)
            throws IOException {
        String path = rawPath;
        String query = "";
        int qIdx = rawPath.indexOf('?');
        if (qIdx >= 0) {
            path = rawPath.substring(0, qIdx);
            query = rawPath.substring(qIdx + 1);
        }

        if ("GET".equals(method) && "/".equals(path)) {
            writeResponse(out, 200, "text/html; charset=utf-8", WebUi.HTML_BYTES);
            return;
        }
        if ("GET".equals(method) && "/api/status".equals(path)) {
            writeJson(out, 200, handler.statusJson());
            return;
        }
        if ("GET".equals(method) && "/api/backup".equals(path)) {
            if (!checkPin(headers, out)) return;
            String backup = handler.exportBackupJson();
            if (backup == null) {
                writeJson(out, 500, "{\"ok\":false,\"error\":\"Export failed\"}");
            } else {
                writeResponse(out, 200, "application/json; charset=utf-8", backup.getBytes(StandardCharsets.UTF_8));
            }
            return;
        }
        if ("POST".equals(method) && "/api/backup".equals(path)) {
            if (!checkPin(headers, out)) return;
            int contentLength = parseInt(headers.get("content-length"), -1);
            if (contentLength <= 0 || contentLength > SettingsBackup.MAX_BACKUP_BYTES) {
                writeJson(out, 413, "{\"ok\":false,\"error\":\"Invalid backup size\"}");
                return;
            }
            byte[] data = readBounded(body, contentLength, SettingsBackup.MAX_BACKUP_BYTES);
            Result r = handler.importBackupJson(new String(data, StandardCharsets.UTF_8));
            writeResult(out, r);
            return;
        }

        if ("POST".equals(method) && ("/api/wallpaper".equals(path) || "/api/clear".equals(path))) {
            if (!checkPin(headers, out)) return;
            String target = queryParam(query, "target");
            if (!"home".equals(target) && !"screensaver".equals(target) && !"both".equals(target)) {
                writeJson(out, 400, "{\"ok\":false,\"error\":\"Invalid target\"}");
                return;
            }
            if ("/api/clear".equals(path)) {
                writeResult(out, handler.clearWallpaper(target));
                return;
            }
            int contentLength = parseInt(headers.get("content-length"), -1);
            if (contentLength <= 0 || contentLength > WallpaperSyncService.MAX_BYTES) {
                writeJson(out, 413, "{\"ok\":false,\"error\":\"Image must be between 1 B and 15 MB\"}");
                return;
            }
            byte[] imageBytes = readBounded(body, contentLength, WallpaperSyncService.MAX_BYTES);
            writeResult(out, handler.applyWallpaper(target, imageBytes));
            return;
        }

        writeJson(out, 404, "{\"ok\":false,\"error\":\"Not found\"}");
    }

    private boolean checkPin(Map<String, String> headers, OutputStream out) throws IOException {
        String expectedPin = pin;
        String givenPin = headers.get("x-pin");
        if (expectedPin != null && !expectedPin.isEmpty()) {
            byte[] a = expectedPin.getBytes(StandardCharsets.UTF_8);
            byte[] b = (givenPin == null ? "" : givenPin.trim()).getBytes(StandardCharsets.UTF_8);
            if (!MessageDigest.isEqual(a, b)) {
                writeJson(out, 403, "{\"ok\":false,\"error\":\"Invalid pairing PIN\"}");
                return false;
            }
        }
        return true;
    }

    static boolean isSupportedImageHeader(byte[] head, int len) {
        if (head == null || len < 4) return false;
        if ((head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xD8 && (head[2] & 0xFF) == 0xFF) return true;
        if (len >= 8 && (head[0] & 0xFF) == 0x89 && head[1] == 'P' && head[2] == 'N' && head[3] == 'G'
                && head[4] == 0x0D && head[5] == 0x0A && head[6] == 0x1A && head[7] == 0x0A) return true;
        return len >= 12 && head[0] == 'R' && head[1] == 'I' && head[2] == 'F' && head[3] == 'F'
                && head[8] == 'W' && head[9] == 'E' && head[10] == 'B' && head[11] == 'P';
    }

    private static byte[] readBounded(InputStream in, int contentLength, long maxBytes) throws IOException {
        if (contentLength <= 0 || contentLength > maxBytes) throw new IOException("Invalid content length");
        byte[] buf = new byte[contentLength];
        int offset = 0;
        while (offset < contentLength) {
            int n = in.read(buf, offset, contentLength - offset);
            if (n < 0) throw new IOException("Unexpected EOF");
            offset += n;
        }
        return buf;
    }

    private static void writeResult(OutputStream out, Result r) throws IOException {
        if (r.ok) {
            writeJson(out, 200, "{\"ok\":true}");
        } else {
            String msg = r.error == null ? "Failed" : r.error.replace("\"", "'");
            writeJson(out, r.httpCode, "{\"ok\":false,\"error\":\"" + msg + "\"}");
        }
    }

    private static void writeJson(OutputStream out, int code, String json) throws IOException {
        writeResponse(out, code, "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8));
    }

    private static void writeResponse(OutputStream out, int code, String contentType, byte[] body) throws IOException {
        String status = code == 200 ? "OK" : code == 403 ? "Forbidden" : code == 404 ? "Not Found" : "Error";
        String header = "HTTP/1.1 " + code + " " + status + "\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n"
                + "Cache-Control: no-store\r\n\r\n";
        out.write(header.getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }

    static String queryParam(String query, String key) {
        if (query == null || query.isEmpty()) return "";
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && key.equals(pair.substring(0, eq))) return pair.substring(eq + 1);
        }
        return "";
    }

    private static int parseInt(String s, int fallback) {
        if (s == null) return fallback;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') break;
            if (b != '\r') buf.write(b);
            if (buf.size() > 8192) throw new IOException("Header line too long");
        }
        if (b == -1 && buf.size() == 0) return null;
        return new String(buf.toByteArray(), StandardCharsets.US_ASCII);
    }
}
