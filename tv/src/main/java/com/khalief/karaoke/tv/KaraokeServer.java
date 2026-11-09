package com.khalief.karaoke.tv;

import fi.iki.elonen.NanoHTTPD;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
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

    /** Mic HP (v1.3.0): suara mentah dari HP diteruskan ke sisi native TV
        untuk diputar via AudioTrack. Server tetap bebas API Android. */
    public interface MicListener {
        void onMicAudio(byte[] data, int length);
        void onMicState(boolean on);
        /** Volume mic 0.0-1.0, berlaku langsung walau mic sedang ON. */
        void onMicVolume(float volume);
    }

    private volatile MicListener micListener;

    public void setMicListener(MicListener l) { micListener = l; }

    public static final int HTTP_PORT = 8080;
    public static final int WS_PORT = 8081;

    /** v1.3.9: pasangan port (HTTP, WS) yang dicoba berurutan. Kalau 8080/8081
        dipakai aplikasi lain di TV, server pindah ke pasangan berikut supaya
        QR tetap muncul, bukan mati diam-diam. */
    public static final int[][] PORT_PAIRS = {{8080, 8081}, {8082, 8083}, {18080, 18081}, {8888, 8889}};

    /* Rotasi kode pairing: selagi tidak ada HP terhubung, kode diganti
       berkala supaya tiap sesi dapat QR baru (minta user 6 Nov 2026).
       Default 30 detik, bisa di-override buat tes:
       -Dkaraoke.code.rotationMs=3000 */
    public static final long CODE_ROTATION_MS = rotationMs();
    public static final long CODE_GRACE_MS = 60000;

    private static long rotationMs() {
        try {
            return Long.parseLong(System.getProperty("karaoke.code.rotationMs", "30000"));
        } catch (Exception e) {
            return 30000;
        }
    }

    public static String newCode() {
        return String.format("%06d", 100000 + new java.security.SecureRandom().nextInt(900000));
    }

    private final String ip;
    private volatile String code;
    private volatile String prevCode;
    private volatile long prevCodeUntil;
    private volatile long codeChangedAt;
    private final java.util.concurrent.atomic.AtomicInteger phoneCount =
            new java.util.concurrent.atomic.AtomicInteger(0);
    private final java.util.concurrent.ScheduledExecutorService rotator;
    private final AssetProvider assets;
    private final QrProvider qr;
    private KaraokeHttpServer http;
    private KaraokeWsServer ws;
    private int httpPort = -1;
    private int wsPort = -1;

    public KaraokeServer(String ip, String code, AssetProvider assets, QrProvider qr) {
        this.ip = ip;
        this.code = code;
        this.assets = assets;
        this.qr = qr;
        this.codeChangedAt = System.currentTimeMillis();
        java.util.concurrent.ThreadFactory daemon = new java.util.concurrent.ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "karaoke-code-rotation");
                t.setDaemon(true);
                return t;
            }
        };
        this.rotator = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(daemon);
        this.rotator.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() { rotationTick(); }
        }, 1, 1, java.util.concurrent.TimeUnit.SECONDS);
    }

    /** Coba tiap pasangan port sampai satu berhasil. Mengembalikan port HTTP
        yang dipakai. Kalau semua gagal, lempar IOException terakhir. */
    public int start() throws IOException {
        IOException last = null;
        for (int[] pair : PORT_PAIRS) {
            int hp = pair[0];
            int wp = pair[1];
            if (!portFree(hp) || !portFree(wp)) continue;
            KaraokeHttpServer h = new KaraokeHttpServer(hp, assets, qr, this);
            KaraokeWsServer w = new KaraokeWsServer(new InetSocketAddress(wp), this);
            try {
                h.start();
            } catch (IOException e) {
                last = e;
                try { h.stop(); } catch (Exception ignored) {}
                continue;
            }
            w.start();
            if (!w.waitStarted(2000)) {
                last = w.startError != null
                        ? new IOException(w.startError.getMessage())
                        : new IOException("websocket tidak bisa jalan di port " + wp);
                try { h.stop(); } catch (Exception ignored) {}
                try { w.stop(100); } catch (Exception ignored) {}
                continue;
            }
            http = h;
            ws = w;
            httpPort = hp;
            wsPort = wp;
            return httpPort;
        }
        if (last != null) throw last;
        throw new IOException("semua port dicoba sudah dipakai");
    }

    public int getHttpPort() { return httpPort; }
    public int getWsPort() { return wsPort; }

    /** Cek cepat apakah port TCP bebas (bind lalu lepas). */
    private static boolean portFree(int port) {
        ServerSocket s = null;
        try {
            s = new ServerSocket(port);
            return true;
        } catch (IOException e) {
            return false;
        } finally {
            if (s != null) {
                try { s.close(); } catch (IOException ignored) {}
            }
        }
    }

    public void stop() {
        try { rotator.shutdownNow(); } catch (Exception ignored) {}
        try { if (http != null) http.stop(); } catch (Exception ignored) {}
        try { if (ws != null) ws.stop(1000); } catch (Exception ignored) {}
    }

    public String getCode() { return code; }
    public String getIp() { return ip; }

    public boolean isPhoneConnected() { return phoneCount.get() > 0; }

    /** Sisa detik sampai kode diganti; -1 kalau rotasi dijeda (HP terhubung). */
    public int getCodeExpiresIn() {
        if (phoneCount.get() > 0) return -1;
        long left = CODE_ROTATION_MS - (System.currentTimeMillis() - codeChangedAt);
        return (int) Math.max(0, left / 1000);
    }

    /** Kode yang diterima saat auth: kode aktif, atau kode lama yang masih
        dalam masa tenggang (HP yang scan tepat sebelum rotasi tetap bisa masuk). */
    public boolean checkCode(String got) {
        if (got == null) return false;
        if (got.equals(code)) return true;
        return prevCode != null && got.equals(prevCode)
                && System.currentTimeMillis() < prevCodeUntil;
    }

    /** Ganti kode sekarang (dipanggil timer saat idle, dan langsung saat
        HP terakhir putus supaya QR baru langsung tersedia). */
    public synchronized void rotateCodeNow() {
        prevCode = code;
        prevCodeUntil = System.currentTimeMillis() + CODE_GRACE_MS;
        code = newCode();
        codeChangedAt = System.currentTimeMillis();
    }

    private void rotationTick() {
        try {
            if (phoneCount.get() == 0
                    && System.currentTimeMillis() - codeChangedAt >= CODE_ROTATION_MS) {
                rotateCodeNow();
            }
        } catch (Exception ignored) {}
    }

    void phoneConnected() {
        phoneCount.incrementAndGet();
    }

    void phoneDisconnected() {
        if (phoneCount.decrementAndGet() <= 0) {
            phoneCount.set(0);
            /* HP terakhir putus: sesi selesai, QR baru langsung,
               dan TV balik ke menu awal */
            rotateCodeNow();
            ws.sendToTv("{\"type\":\"peer_left\"}");
        }
    }

    public String controllerUrl() {
        int p = httpPort > 0 ? httpPort : HTTP_PORT;
        return "http://" + ip + ":" + p + "/?mode=phone&code=" + code;
    }

    public String infoJson() {
        int wp = wsPort > 0 ? wsPort : WS_PORT;
        return "{\"code\":\"" + code + "\",\"ip\":\"" + ip + "\",\"wsPort\":" + wp
                + ",\"phoneConnected\":" + isPhoneConnected()
                + ",\"codeExpiresIn\":" + getCodeExpiresIn() + "}";
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
        private final KaraokeServer parent;
        private final Set<WebSocket> authed =
                Collections.newSetFromMap(new ConcurrentHashMap<WebSocket, Boolean>());
        private final Map<WebSocket, String> roles = new ConcurrentHashMap<>();
        /* v1.3.9: penanda hasil start. Java-WebSocket tidak melempar saat
           bind gagal, melainkan memanggil onError tanpa koneksi. start()
           di luar memakai dua flag ini untuk tahu apakah server WS
           benar-benar listen. */
        volatile boolean startedOk = false;
        volatile Exception startError = null;

        /** Tunggu sampai onStart terpanggil (server WS benar-benar listen),
            atau sampai onError tanpa koneksi (gagal bind). */
        boolean waitStarted(long timeoutMs) {
            long end = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < end) {
                if (startedOk) return true;
                if (startError != null) return false;
                try { Thread.sleep(50); } catch (InterruptedException e) { return false; }
            }
            return startedOk;
        }
        /* Koneksi mic native HP (auth dengan "mic":true): ditandai supaya
           saat koneksi ini putus, TV tahu mic berhenti walau UI HP masih
           terhubung. */
        private final Set<WebSocket> micConns =
                Collections.newSetFromMap(new ConcurrentHashMap<WebSocket, Boolean>());

        KaraokeWsServer(InetSocketAddress addr, KaraokeServer parent) {
            super(addr);
            this.parent = parent;
        }

        /** Kirim pesan ke semua halaman TV yang sudah auth. */
        void sendToTv(String message) {
            for (WebSocket c : getConnections()) {
                if (authed.contains(c) && "tv".equals(roles.get(c)) && c.isOpen()) {
                    try { c.send(message); } catch (Exception ignored) {}
                }
            }
        }

        @Override
        public void onOpen(WebSocket conn, ClientHandshake handshake) {
            // klien wajib kirim {type:"auth", ...} dulu, kalau tidak pesannya dibuang
        }

        @Override
        public void onClose(WebSocket conn, int code, String reason, boolean remote) {
            String role = roles.get(conn);
            authed.remove(conn);
            roles.remove(conn);
            /* Koneksi mic putus (HP mati / stream dimatikan): matikan
               pemutaran mic di TV + hilangkan indikator di layar TV.
               v1.3.1: hanya kalau tidak ada lagi koneksi mic lain -
               kalau HP baru saja auth mic pengganti, jangan kirim
               mic-off palsu yang mematikan mic yang baru jalan. */
            if (micConns.remove(conn) && micConns.isEmpty()) {
                MicListener l = parent.micListener;
                if (l != null) {
                    try { l.onMicState(false); } catch (Exception ignored) {}
                }
                sendToTv("{\"type\":\"mic\",\"on\":false}");
            }
            if ("phone".equals(role)) {
                parent.phoneDisconnected();
            }
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
                    if (parent.checkCode(got)) {
                        authed.add(conn);
                        roles.put(conn, "phone");
                        if (Boolean.TRUE.equals(jsonBool(message, "mic"))) {
                            /* Hanya satu HP dalam satu sesi: koneksi mic
                               lama yang masih tercatat (mis. soket
                               setengah mati) dibuang supaya tidak
                               menyangkut saat HP sambung ulang mic. */
                            for (WebSocket old : new java.util.ArrayList<>(micConns)) {
                                if (old != conn && old.isOpen()) {
                                    try { old.close(4000, "mic replaced"); }
                                    catch (Exception ignored) {}
                                }
                            }
                            micConns.add(conn);
                        }
                        parent.phoneConnected();
                        conn.send("{\"type\":\"hello\",\"ok\":true}");
                    } else {
                        conn.close(4001, "bad code");
                    }
                    return;
                }
                conn.close(4001, "bad auth");
                return;
            }
            /* Mic HP on/off: beri tahu sisi native TV (putar/berhenti
               AudioTrack) lalu teruskan ke halaman TV untuk indikator. */
            if ("mic".equals(jsonField(message, "type"))) {
                Boolean on = jsonBool(message, "on");
                if (on != null && parent.micListener != null) {
                    try { parent.micListener.onMicState(on); } catch (Exception ignored) {}
                }
            }
            /* Volume mic (0-100): beri tahu sisi native TV, diterapkan
               sebagai gain AudioTrack mic, berlaku langsung saat mic ON. */
            if ("mic_vol".equals(jsonField(message, "type"))) {
                Integer v = jsonInt(message, "value");
                if (v != null && parent.micListener != null) {
                    float vol = Math.max(0, Math.min(100, v)) / 100f;
                    try { parent.micListener.onMicVolume(vol); } catch (Exception ignored) {}
                }
            }
            // relay ke semua klien lain yang sudah auth
            Collection<WebSocket> all = getConnections();
            for (WebSocket c : all) {
                if (c != conn && authed.contains(c) && c.isOpen()) {
                    c.send(message);
                }
            }
        }

        /* Frame biner dari HP: suara mic mentah (PCM 16-bit mono 16kHz,
           10ms per frame). Diteruskan ke sisi native TV saja, TIDAK
           di-relay ke halaman web (halaman cuma butuh indikator on/off). */
        @Override
        public void onMessage(WebSocket conn, ByteBuffer bytes) {
            if (!authed.contains(conn)) return;
            if (!"phone".equals(roles.get(conn))) return;
            if (!micConns.contains(conn)) return; // hanya koneksi mic
            MicListener l = parent.micListener;
            if (l == null) return;
            byte[] data = new byte[bytes.remaining()];
            bytes.get(data);
            try { l.onMicAudio(data, data.length); } catch (Exception ignored) {}
        }

        @Override
        public void onError(WebSocket conn, Exception ex) {
            // v1.3.9: error tanpa koneksi = gagal start (mis. port sudah
            // dipakai). Simpan supaya start() di luar bisa pindah port.
            if (conn == null) {
                startError = ex;
                return;
            }
            // koneksi bermasalah: bersihkan dari daftar. v1.3.1: kalau
            // yang rusak itu koneksi mic, beri tahu TV seperti onClose
            // (dulu cuma dihapus diam-diam, mic bisa nyangkut jalan).
            if (conn != null) {
                authed.remove(conn);
                roles.remove(conn);
                if (micConns.remove(conn) && micConns.isEmpty()) {
                    MicListener l = parent.micListener;
                    if (l != null) {
                        try { l.onMicState(false); } catch (Exception ignored) {}
                    }
                    sendToTv("{\"type\":\"mic\",\"on\":false}");
                }
            }
        }

        @Override
        public void onStart() {
            // server WS jalan
            startedOk = true;
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

    /** Ambil nilai field boolean sederhana dari JSON datar ("on":true). */
    static Boolean jsonBool(String json, String name) {
        if (json == null) return null;
        String key = "\"" + name + "\"";
        int i = json.indexOf(key);
        if (i < 0) return null;
        int c = json.indexOf(':', i + key.length());
        if (c < 0) return null;
        int j = c + 1;
        while (j < json.length() && Character.isWhitespace(json.charAt(j))) j++;
        if (json.startsWith("true", j)) return Boolean.TRUE;
        if (json.startsWith("false", j)) return Boolean.FALSE;
        return null;
    }

    /** Ambil nilai field angka bulat dari JSON datar ("value":75). */
    static Integer jsonInt(String json, String name) {
        if (json == null) return null;
        String key = "\"" + name + "\"";
        int i = json.indexOf(key);
        if (i < 0) return null;
        int c = json.indexOf(':', i + key.length());
        if (c < 0) return null;
        int j = c + 1;
        while (j < json.length() && Character.isWhitespace(json.charAt(j))) j++;
        int k = j;
        if (k < json.length() && json.charAt(k) == '-') k++;
        while (k < json.length() && Character.isDigit(json.charAt(k))) k++;
        if (k == j) return null;
        try { return Integer.parseInt(json.substring(j, k)); }
        catch (Exception e) { return null; }
    }
}
