package com.khalief.karaoke.tv;

import fi.iki.elonen.NanoHTTPD;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server karaoke di TV: HTTP port 8080 (UI web + QR + info) dan
 * WebSocket port 8081 (relay perintah HP ke halaman TV dan sebaliknya).
 * Kode ini sengaja bebas dari API Android supaya bisa diuji di JVM biasa.
 */
public class KaraokeServer {

    public interface AssetProvider {
        InputStream open(String path) throws IOException;
    }

    public interface QrProvider {
        byte[] getQrPng() throws Exception;
    }

    public static final int HTTP_PORT = 8080;
    public static final int WS_PORT = 8081;

    private final String ip;
    private final String code;
    private final KaraokeHttpServer http;
    private final KaraokeWsServer ws;

    public KaraokeServer(String ip, String code, AssetProvider assets, QrProvider qr) {
        this.ip = ip;
        this.code = code;
        this.http = new KaraokeHttpServer(HTTP_PORT, assets, qr, this);
        this.ws = new KaraokeWsServer(new InetSocketAddress(WS_PORT), code);
    }

    public void start() throws IOException {
        http.start();
        ws.start();
    }

    public void stop() {
        try { http.stop(); } catch (Exception ignored) {}
        try { ws.stop(1000); } catch (Exception ignored) {}
    }

    public String getCode() { return code; }
    public String getIp() { return ip; }

    public String controllerUrl() {
        return "http://" + ip + ":" + HTTP_PORT + "/?mode=phone&code=" + code;
    }

    public String infoJson() {
        return "{\"code\":\"" + code + "\",\"ip\":\"" + ip + "\",\"wsPort\":" + WS_PORT + "}";
    }

    /* ---------- HTTP ---------- */

    static class KaraokeHttpServer extends NanoHTTPD {
        private final AssetProvider assets;
        private final QrProvider qr;
        private final KaraokeServer parent;

        KaraokeHttpServer(int port, AssetProvider assets, QrProvider qr, KaraokeServer parent) {
            super(port);
            this.assets = assets;
            this.qr = qr;
            this.parent = parent;
        }

        @Override
        public Response serve(IHTTPSession session) {
            String uri = session.getUri();
            if (uri.equals("/") || uri.equals("/index.html")) {
                return serveAsset("index.html", "text/html");
            }
            if (uri.equals("/app.js")) {
                return serveAsset("app.js", "text/javascript");
            }
            if (uri.equals("/style.css")) {
                return serveAsset("style.css", "text/css");
            }
            if (uri.equals("/qr.png")) {
                try {
                    byte[] png = qr.getQrPng();
                    return newFixedLengthResponse(Response.Status.OK, "image/png",
                            new ByteArrayInputStream(png), png.length);
                } catch (Exception e) {
                    return newFixedLengthResponse(Response.Status.INTERNAL_ERROR,
                            "text/plain", "qr error: " + e.getMessage());
                }
            }
            if (uri.equals("/api/info")) {
                return newFixedLengthResponse(Response.Status.OK,
                        "application/json", parent.infoJson());
            }
            if (uri.equals("/api/search")) {
                return searchProxy(session);
            }
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "not found");
        }

        /* Proxy pencarian: HP cuma manggil server TV (same-origin, bebas CORS),
           server TV yang fetch ke instance Invidious. Instance publik sering
           mati / matikan API-nya, jadi dicoba berantai dan jawabannya divalidasi
           (harus JSON array berisi videoId, bukan halaman HTML error).
           Daftar host bisa di-override buat tes lokal:
           -Dkaraoke.search.hosts=http://127.0.0.1:18080,https://... */
        private static final String[] SEARCH_HOSTS = searchHosts();

        private static String[] searchHosts() {
            String prop = System.getProperty("karaoke.search.hosts");
            if (prop != null && !prop.trim().isEmpty()) {
                return prop.trim().split("\\s*,\\s*");
            }
            return new String[] {
                "https://yt.chocolatemoo53.com",
                "https://invidious.materialio.us"
            };
        }

        private Response searchProxy(IHTTPSession session) {
            String q = null;
            try {
                java.util.List<String> vals = session.getParameters().get("q");
                if (vals != null && !vals.isEmpty()) q = vals.get(0);
            } catch (Exception ignored) {}
            if (q == null || q.trim().isEmpty()) {
                return newFixedLengthResponse(Response.Status.OK,
                        "application/json", "[]");
            }
            String query;
            try {
                query = java.net.URLEncoder.encode(q.trim(), "UTF-8");
            } catch (Exception e) {
                return newFixedLengthResponse(Response.Status.OK,
                        "application/json", "[]");
            }
            for (String host : SEARCH_HOSTS) {
                String body = fetchJson(host + "/api/v1/search?q=" + query + "&type=video");
                if (body != null) {
                    return newFixedLengthResponse(Response.Status.OK,
                            "application/json", body);
                }
            }
            return newFixedLengthResponse(Response.Status.OK,
                    "application/json", "[]");
        }

        private String fetchJson(String urlStr) {
            java.net.HttpURLConnection conn = null;
            try {
                java.net.URL url = new java.net.URL(urlStr);
                conn = (java.net.HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(3000);
                conn.setReadTimeout(5000);
                conn.setRequestMethod("GET");
                conn.setRequestProperty("User-Agent", "KaraokeRemote/1.0");
                conn.setRequestProperty("Accept", "application/json");
                if (conn.getResponseCode() != 200) return null;
                java.io.InputStream in = conn.getInputStream();
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n, total = 0;
                while ((n = in.read(buf)) != -1) {
                    total += n;
                    if (total > 2 * 1024 * 1024) return null;
                    out.write(buf, 0, n);
                }
                String body = out.toString("UTF-8").trim();
                if (!body.startsWith("[")) return null;
                if (body.indexOf("\"videoId\"") < 0) return null;
                return body;
            } catch (Exception e) {
                return null;
            } finally {
                if (conn != null) conn.disconnect();
            }
        }

        private Response serveAsset(String name, String mime) {
            try {
                InputStream in = assets.open(name);
                return newChunkedResponse(Response.Status.OK, mime, in);
            } catch (IOException e) {
                return newFixedLengthResponse(Response.Status.NOT_FOUND,
                        "text/plain", "missing asset: " + name);
            }
        }
    }

    /* ---------- WebSocket relay ---------- */

    static class KaraokeWsServer extends WebSocketServer {
        private final String code;
        private final Set<WebSocket> authed =
                Collections.newSetFromMap(new ConcurrentHashMap<WebSocket, Boolean>());
        private final Map<WebSocket, String> roles = new ConcurrentHashMap<>();

        KaraokeWsServer(InetSocketAddress addr, String code) {
            super(addr);
            this.code = code;
        }

        @Override
        public void onOpen(WebSocket conn, ClientHandshake handshake) {
            // klien wajib kirim {type:"auth", ...} dulu, kalau tidak pesannya dibuang
        }

        @Override
        public void onClose(WebSocket conn, int code, String reason, boolean remote) {
            authed.remove(conn);
            roles.remove(conn);
        }

        @Override
        public void onMessage(WebSocket conn, String message) {
            if (!authed.contains(conn)) {
                String type = jsonField(message, "type");
                if (!"auth".equals(type)) {
                    conn.close(4001, "auth required");
                    return;
                }
                String role = jsonField(message, "role");
                if ("tv".equals(role)) {
                    authed.add(conn);
                    roles.put(conn, "tv");
                    conn.send("{\"type\":\"hello\",\"ok\":true}");
                    return;
                }
                if ("phone".equals(role)) {
                    String got = jsonField(message, "code");
                    if (code.equals(got)) {
                        authed.add(conn);
                        roles.put(conn, "phone");
                        conn.send("{\"type\":\"hello\",\"ok\":true}");
                    } else {
                        conn.close(4001, "bad code");
                    }
                    return;
                }
                conn.close(4001, "bad auth");
                return;
            }
            // relay ke semua klien lain yang sudah auth
            Collection<WebSocket> all = getConnections();
            for (WebSocket c : all) {
                if (c != conn && authed.contains(c) && c.isOpen()) {
                    c.send(message);
                }
            }
        }

        @Override
        public void onError(WebSocket conn, Exception ex) {
            // koneksi bermasalah: bersihkan dari daftar
            if (conn != null) {
                authed.remove(conn);
                roles.remove(conn);
            }
        }

        @Override
        public void onStart() {
            // server WS jalan
        }
    }

    /** Ambil nilai field string sederhana dari JSON datar. Cukup untuk pesan auth. */
    static String jsonField(String json, String name) {
        if (json == null) return null;
        String key = "\"" + name + "\"";
        int i = json.indexOf(key);
        if (i < 0) return null;
        int c = json.indexOf(':', i + key.length());
        if (c < 0) return null;
        int q1 = json.indexOf('"', c);
        if (q1 < 0) return null;
        int q2 = json.indexOf('"', q1 + 1);
        if (q2 < 0) return null;
        return json.substring(q1 + 1, q2);
    }
}
