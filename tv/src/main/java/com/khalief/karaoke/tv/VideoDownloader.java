package com.khalief.karaoke.tv;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Unduh video karaoke ke TV (MP4), atas perintah dari HP.
 *
 * Alur: HP kirim perintah {type:"download", videoId, title} lewat
 * WebSocket -> halaman TV memanggil bridge localDownload() -> kelas
 * ini resolve + unduh di thread background -> progres dilaporkan
 * lewat Listener ke halaman TV (terus ke HP lewat broadcast state).
 *
 * Resolve stream: TIDAK ada yt-dlp di Android (tanpa Python), jadi
 * dipakai endpoint Invidious yang sama dengan pencarian (host yang
 * sama dengan KaraokeServer): GET {host}/api/v1/videos/{videoId}
 * (dulu /api/v1/streams/{videoId}, tapi endpoint itu di kedua host
 * sekarang mengembalikan sampah: satu mengembalikan potongan HTML
 * jebakan bot, satu badan kosong - jadi semua unduhan gagal di
 * resolve dengan no_stream tanpa pernah menyentuh jaringan video).
 *
 * Dua jalur unduh dari hasil resolve:
 * - formatStreams berisi MP4 gabungan (itag 22 720p / 18 360p):
 *   unduh satu file seperti dulu.
 * - Kalau cuma ada DASH terpisah (adaptiveFormats, video dan audio
 *   sendiri-sendiri - ini yang dikembalikan semua instance sekarang,
 *   formatStreams kosong buat semua video): unduh video MP4 avc1
 *   (itag 136 720p, lalu 135/134/133/160) + audio MP4 AAC (itag 140,
 *   lalu 139), lalu gabung jadi satu MP4 pakai MediaMuxer bawaan
 *   Android (tanpa ffmpeg). Video webm/vp9 dan audio opus TIDAK
 *   dipakai - muxer MP4 butuh avc1 + mp4a.
 *
 * Simpan: getExternalFilesDir("download") =
 * /sdcard/Android/data/<package>/files/download/. Nama file dari
 * judul (karakter ilegal dibersihkan), tambah angka kalau sudah ada.
 * Unduh ke file .part dulu, baru rename kalau selesai, supaya file
 * setengah jalan tidak muncul di daftar.
 *
 * TAHAN BANTING (permintaan user 17 Nov 02:40 + 02:41):
 * - Unduhan jalan di TV, bukan di HP: HP putus di tengah jalan
 *   tidak menghentikan unduhan; HP yang sambung lagi tinggal
 *   membaca ulang state dari TV.
 * - Progres disimpan persisten (SharedPreferences) selama unduh
 *   jalan. Kalau aplikasi TV restart di tengah unduhan, unduhan
 *   DILANJUTKAN otomatis begitu VideoDownloader dibuat lagi,
 *   dengan HTTP Range dari ukuran file .part yang sudah ada.
 *   Kalau server menolak Range, file dimulai ulang dari nol.
 * - File .part TIDAK PERNAH dihapus saat gagal, cuma rename
 *   yang menghabiskannya (saat selesai). File yang sudah selesai
 *   tidak pernah ditimpa atau dihapus oleh kode apa pun di sini.
 *   Daftar unduhan = isi folder download itu sendiri, jadi tidak
 *   bisa hilang karena disconnect, reconnect, atau restart.
 */
public class VideoDownloader {

    private static final String TAG = "VideoDownloader";

    /** Host Invidious yang sama dengan pencarian (KaraokeServer).
        Bisa di-override buat tes: -Dkaraoke.search.hosts=... */
    private static final String[] STREAM_HOSTS = streamHosts();

    private static String[] streamHosts() {
        String prop = System.getProperty("karaoke.search.hosts");
        if (prop != null && !prop.trim().isEmpty()) {
            return prop.trim().split("\\s*,\\s*");
        }
        return new String[] {
            "https://yt.chocolatemoo53.com",
            "https://invidious.materialio.us"
        };
    }

    /** Progres unduhan yang sedang jalan, disimpan persisten supaya
        bisa dilanjutkan kalau aplikasi TV restart. */
    private static final String PREFS = "karaoke_download_prefs";
    private static final String K_STATE = "state";
    private static final String K_VIDEO_ID = "videoId";
    private static final String K_TITLE = "title";
    private static final String K_URL = "streamUrl";
    private static final String K_FILE = "fileName";
    private static final String K_DOWNLOADED = "downloaded";
    private static final String K_TOTAL = "total";
    private static final String K_ERROR = "error";
    private static final String K_MESSAGE = "message";
    private static final String K_VIDEO_BYTES = "videoBytes";
    private static final String K_AUDIO_BYTES = "audioBytes";

    /** Dipanggil tiap progres berubah + saat selesai/gagal.
        JSON state, sama dengan stateJson(). */
    public interface Listener {
        void onDownloadProgress(String stateJson);
    }

    private final Context ctx;
    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    // State unduhan aktif (atau terakhir). Diakses dari thread mana
    // saja, jadi semua aksesnya synchronized.
    private String state = "idle"; // idle | downloading | done | failed
    private String videoId = "";
    private String title = "";
    private String streamUrl = "";
    private String fileName = "";
    private long downloaded = 0;
    private long total = -1;
    private String error = "";   // kode: no_stream | network | no_space | busy | bad_id
    private String message = ""; // pesan buat user (Indonesia)
    private boolean busy = false;
    private long lastPersistAt = 0;
    /** Ukuran potongan jalur DASH (buat bukti di status HP): video
        MP4 + audio MP4 yang digabung. Jalur muxed (satu file):
        videoBytes = ukuran file, audioBytes = 0. */
    private long videoBytes = 0;
    private long audioBytes = 0;

    public VideoDownloader(Context context, Listener l) {
        this.ctx = context.getApplicationContext();
        this.listener = l;
        restoreState();
    }

    /** Folder unduhan di TV. */
    public static File downloadDir(Context context) {
        try {
            File d = new File(context.getExternalFilesDir("download"), "");
            if (!d.exists()) d.mkdirs();
            return d;
        } catch (Exception e) {
            return null;
        }
    }

    /** State sekarang sebagai JSON. */
    public synchronized String stateJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("state", state);
            o.put("videoId", videoId);
            o.put("title", title);
            o.put("file", fileName);
            o.put("downloaded", downloaded);
            o.put("total", total);
            o.put("error", error);
            o.put("message", message);
            o.put("videoBytes", videoBytes);
            o.put("audioBytes", audioBytes);
            return o.toString();
        } catch (Exception e) {
            return "{\"state\":\"" + state + "\"}";
        }
    }

    private synchronized void setState(String s, String err, String msg) {
        state = s;
        error = err == null ? "" : err;
        message = msg == null ? "" : msg;
    }

    private void emit() {
        final String json = stateJson();
        mainHandler.post(new Runnable() {
            @Override public void run() {
                try { listener.onDownloadProgress(json); } catch (Exception ignored) {}
            }
        });
    }

    /* ---------- Progres persisten (lanjutkan sesudah restart) ---------- */

    /** Simpan state sekarang ke SharedPreferences. Dipanggil tiap
        ganti state + paling sering 2 detik sekali saat progres jalan. */
    private synchronized void persistState() {
        try {
            SharedPreferences.Editor ed = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
            ed.putString(K_STATE, state);
            ed.putString(K_VIDEO_ID, videoId);
            ed.putString(K_TITLE, title);
            ed.putString(K_URL, streamUrl);
            ed.putString(K_FILE, fileName);
            ed.putLong(K_DOWNLOADED, downloaded);
            ed.putLong(K_TOTAL, total);
            ed.putString(K_ERROR, error);
            ed.putString(K_MESSAGE, message);
            ed.putLong(K_VIDEO_BYTES, videoBytes);
            ed.putLong(K_AUDIO_BYTES, audioBytes);
            ed.apply();
            lastPersistAt = System.currentTimeMillis();
        } catch (Exception ignored) {}
    }

    private synchronized void persistThrottled() {
        if (System.currentTimeMillis() - lastPersistAt >= 2000) persistState();
    }

    /** Hapus catatan progres tersimpan (dipakai kalau unduhan selesai:
        file-nya sendiri yang jadi catatan). */
    private synchronized void clearPersisted() {
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply();
        } catch (Exception ignored) {}
    }

    /** Baca catatan progres terakhir. Kalau statusnya "downloading"
        saat aplikasi mati/restart, LANJUTKAN otomatis. Kalau
        "failed", muat saja supaya UI tetap bisa menampilkan
        alasannya + tombol unduh yang sama bisa mengulang dari
        file .part yang tersisa. Kalau "done", buang catatannya
        (file sudah ada di folder, itu catatannya). */
    private void restoreState() {
        String savedState;
        try {
            SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            savedState = p.getString(K_STATE, "idle");
            if (!"downloading".equals(savedState) && !"failed".equals(savedState)) {
                if (!"idle".equals(savedState)) clearPersisted();
                return;
            }
            synchronized (this) {
                state = savedState;
                videoId = p.getString(K_VIDEO_ID, "");
                title = p.getString(K_TITLE, "");
                streamUrl = p.getString(K_URL, "");
                fileName = p.getString(K_FILE, "");
                downloaded = p.getLong(K_DOWNLOADED, 0);
                total = p.getLong(K_TOTAL, -1);
                error = p.getString(K_ERROR, "");
                message = p.getString(K_MESSAGE, "");
                videoBytes = p.getLong(K_VIDEO_BYTES, 0);
                audioBytes = p.getLong(K_AUDIO_BYTES, 0);
            }
        } catch (Exception e) {
            return;
        }
        if ("downloading".equals(savedState) && videoId.length() > 0 && fileName.length() > 0) {
            Log.i(TAG, "melanjutkan unduhan tertunda: " + fileName);
            synchronized (this) { busy = true; }
            emit();
            final String vid = videoId;
            final String ttl = title;
            final String fname = fileName;
            worker.execute(new Runnable() {
                @Override public void run() { resumeDownload(vid, ttl, fname); }
            });
        }
    }

    /** Mulai unduh. Mengembalikan JSON {"ok":true} atau
        {"ok":false,"reason":"busy"|"bad_id"}. Cuma satu unduhan
        dalam satu waktu; yang kedua ditolak dengan "busy". */
    public synchronized String start(final String videoId, final String title) {
        if (busy) return "{\"ok\":false,\"reason\":\"busy\"}";
        if (videoId == null || !videoId.matches("[A-Za-z0-9_-]{6,20}")) {
            return "{\"ok\":false,\"reason\":\"bad_id\"}";
        }
        busy = true;
        this.videoId = videoId;
        this.title = title == null ? videoId : title;
        this.streamUrl = "";
        this.fileName = "";
        this.downloaded = 0;
        this.total = -1;
        this.videoBytes = 0;
        this.audioBytes = 0;
        setState("downloading", "", "");
        persistState();
        emit();
        final String vid = videoId;
        final String ttl = this.title;
        worker.execute(new Runnable() {
            @Override public void run() { runDownload(vid, ttl); }
        });
        return "{\"ok\":true}";
    }

    /** Bersihkan thread saat activity mati. Unduhan yang jalan TIDAK
        dibatalkan logikanya: state "downloading" tetap tersimpan,
        dan VideoDownloader berikutnya akan melanjutkannya. */
    public synchronized void destroy() {
        try { worker.shutdownNow(); } catch (Exception ignored) {}
    }

    private void runDownload(String vid, String ttl) {
        try {
            StreamSet set = resolveStreams(vid);
            if (set == null) {
                fail("no_stream", "Server sumber tidak memberi tautan unduhan buat video ini. Coba lagu lain, atau coba lagi nanti.");
                return;
            }
            synchronized (this) {
                streamUrl = set.muxedUrl != null ? set.muxedUrl : set.videoUrl;
                persistState();
            }
            if (set.muxedUrl != null) {
                downloadToFile(vid, ttl, set.muxedUrl, null, 0);
            } else {
                downloadDashPair(vid, ttl, set, null);
            }
        } catch (Exception e) {
            Log.w(TAG, "unduh gagal: " + e);
            fail("network", "Unduhan gagal: " + shortMsg(e));
        }
    }

    /** Lanjutkan unduhan yang terputus karena aplikasi restart.
        URL stream di-resolve ulang (URL lama bisa kedaluwarsa),
        lalu unduhan dilanjutkan dari ukuran file .part yang ada. */
    private void resumeDownload(String vid, String ttl, String fname) {
        try {
            StreamSet set = resolveStreams(vid);
            if (set == null) {
                fail("no_stream", "Tidak bisa melanjutkan: server sumber tidak memberi tautan unduhan buat video ini.");
                return;
            }
            synchronized (this) {
                streamUrl = set.muxedUrl != null ? set.muxedUrl : set.videoUrl;
                persistState();
            }
            if (set.muxedUrl != null) {
                File dir = downloadDir(ctx);
                long offset = 0;
                if (dir != null) {
                    File part = new File(dir, "." + fname + ".part");
                    if (part.isFile()) offset = part.length();
                }
                synchronized (this) { downloaded = offset; }
                downloadToFile(vid, ttl, set.muxedUrl, fname, offset);
            } else {
                downloadDashPair(vid, ttl, set, fname);
            }
        } catch (Exception e) {
            Log.w(TAG, "lanjut unduhan gagal: " + e);
            fail("network", "Melanjutkan unduhan gagal: " + shortMsg(e));
        }
    }

    private void fail(String err, String msg) {
        synchronized (this) {
            setState("failed", err, msg);
            busy = false;
            persistState();
        }
        emit();
    }

    private void done(String name) {
        done(name, "");
    }

    private void done(String name, String msg) {
        synchronized (this) {
            fileName = name;
            setState("done", "", msg);
            busy = false;
            clearPersisted();
        }
        emit();
    }

    private static String shortMsg(Exception e) {
        String m = e.getMessage();
        if (m == null || m.length() == 0) return "cek internet lalu coba lagi.";
        if (m.length() > 80) m = m.substring(0, 80);
        return m;
    }

    /** Hasil resolve: satu URL MP4 gabungan, atau pasangan DASH
        (video + audio terpisah, keduanya MP4). Salah satu harus ada
        supaya bisa diunduh; muxed diutamakan. */
    private static class StreamSet {
        String muxedUrl; // itag 22/18, video+audio
        String videoUrl; // adaptive video MP4 avc1 (itag 136/135/134/133/160)
        String audioUrl; // adaptive audio MP4 AAC (itag 140/139)
        boolean isDash() { return muxedUrl == null && videoUrl != null && audioUrl != null; }
        boolean usable() { return muxedUrl != null || isDash(); }
    }

    /** Cari URL stream dari Invidious. Tiap host dicoba endpoint
        /api/v1/videos/ dulu (mengembalikan JSON beneran, lengkap
        dengan adaptiveFormats), baru /api/v1/streams/ sebagai
        cadangan. Mengembalikan null kalau tidak satu pun host memberi
        set yang bisa dipakai. */
    private StreamSet resolveStreams(String vid) {
        for (String host : STREAM_HOSTS) {
            for (String path : new String[]{"/api/v1/videos/", "/api/v1/streams/"}) {
                String body = fetchText(host + path + vid, 5000, 15000, 2 * 1024 * 1024);
                if (body == null) continue;
                try {
                    StreamSet set = parseStreamSet(new JSONObject(body));
                    if (set != null && set.usable()) return set;
                } catch (Exception e) {
                    Log.w(TAG, "parse streams gagal (" + host + path + "): " + e);
                }
            }
        }
        return null;
    }

    /** Baca formatStreams + adaptiveFormats dari satu jawaban
        Invidious (/videos/ atau /streams/). null kalau tidak ada
        yang bisa dipakai. */
    private static StreamSet parseStreamSet(JSONObject root) {
        StreamSet set = new StreamSet();
        JSONArray formats = root.optJSONArray("formatStreams");
        if (formats != null) {
            String best18 = null;
            for (int i = 0; i < formats.length(); i++) {
                JSONObject f = formats.optJSONObject(i);
                if (f == null) continue;
                String url = f.optString("url", "");
                if (url == null || url.length() == 0) continue;
                String container = f.optString("container", "");
                String type = f.optString("type", "");
                boolean mp4 = "mp4".equalsIgnoreCase(container)
                        || (type != null && type.contains("mp4"));
                if (!mp4) continue;
                int itag = f.optInt("itag", 0);
                if (itag == 22) { set.muxedUrl = url; return set; }
                if (itag == 18 && best18 == null) best18 = url;
            }
            if (best18 != null) { set.muxedUrl = best18; return set; }
        }
        JSONArray adaptive = root.optJSONArray("adaptiveFormats");
        if (adaptive != null) {
            set.videoUrl = pickAdaptive(adaptive, true,
                    new int[]{136, 135, 134, 133, 160});
            set.audioUrl = pickAdaptive(adaptive, false,
                    new int[]{140, 139});
        }
        return set.usable() ? set : null;
    }

    /** Pilih satu format adaptive MP4: video harus avc1, audio harus
        mp4a. Urutan itag = kualitas tertinggi dulu. null kalau tidak
        ada yang cocok. */
    private static String pickAdaptive(JSONArray adaptive, boolean video, int[] wantItags) {
        for (int want : wantItags) {
            for (int i = 0; i < adaptive.length(); i++) {
                JSONObject f = adaptive.optJSONObject(i);
                if (f == null) continue;
                if (f.optInt("itag", 0) != want) continue;
                String type = f.optString("type", "");
                if (type == null || !type.startsWith(video ? "video/mp4" : "audio/mp4")) continue;
                if (video && !type.contains("avc1")) continue;
                if (!video && !type.contains("mp4a")) continue;
                String url = f.optString("url", "");
                if (url != null && url.length() > 0) return url;
            }
        }
        return null;
    }

    /** GET teks biasa (dipakai resolve stream). null kalau gagal. */
    private String fetchText(String urlStr, int connectMs, int readMs, int maxBytes) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(connectMs);
            conn.setReadTimeout(readMs);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "KaraokeRemote/1.0");
            conn.setRequestProperty("Accept", "application/json");
            if (conn.getResponseCode() != 200) return null;
            InputStream in = conn.getInputStream();
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n, total = 0;
            while ((n = in.read(buf)) != -1) {
                total += n;
                if (total > maxBytes) return null;
                out.write(buf, 0, n);
            }
            return out.toString("UTF-8");
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** Unduh URL stream ke folder download, dengan progres.
        nameHint = nama file yang sudah ditentukan (resume), atau null
        (baru: nama dibuat dari judul). startOffset = byte awal kalau
        melanjutkan file .part yang sudah ada. File .part TIDAK PERNAH
        dihapus saat gagal, supaya bisa dilanjutkan nanti. */
    private void downloadToFile(String vid, String ttl, String streamUrl,
                                String nameHint, long startOffset) {
        File dir = downloadDir(ctx);
        if (dir == null || !dir.isDirectory()) {
            fail("no_space", "Folder unduhan tidak bisa dibuat di TV ini.");
            return;
        }
        String name = nameHint != null ? nameHint : uniqueFileName(dir, sanitizeFileName(ttl));
        File tmp = new File(dir, "." + name + ".part");
        File fin = new File(dir, name);
        if (fin.exists()) {
            // Sudah selesai diunduh sebelumnya (mis. retry sesudah
            // selesai): anggap selesai, jangan timpa file yang ada.
            done(name);
            return;
        }
        long offset = startOffset;
        if (offset > 0 && !tmp.isFile()) offset = 0;
        if (offset > 0 && tmp.length() < offset) offset = tmp.length();
        HttpURLConnection conn = null;
        try {
            URL url = new URL(streamUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(30000);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "KaraokeRemote/1.0");
            conn.setInstanceFollowRedirects(true);
            if (offset > 0) {
                conn.setRequestProperty("Range", "bytes=" + offset + "-");
            }
            int code = conn.getResponseCode();
            if (code == 416) {
                // Range tidak masuk akal (file .part lebih besar dari
                // video): mulai dari nol.
                try { tmp.delete(); } catch (Exception ignored) {}
                offset = 0;
                conn.disconnect();
                conn = (HttpURLConnection) new URL(streamUrl).openConnection();
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(30000);
                conn.setRequestMethod("GET");
                conn.setRequestProperty("User-Agent", "KaraokeRemote/1.0");
                conn.setInstanceFollowRedirects(true);
                code = conn.getResponseCode();
            } else if (code == 200 && offset > 0) {
                // Server mengabaikan Range: mulai dari nol.
                try { tmp.delete(); } catch (Exception ignored) {}
                offset = 0;
            }
            if (code != 200 && code != 206) {
                fail("network", "Server video menolak unduhan (HTTP " + code + "). Coba video lain.");
                return;
            }
            long len = conn.getContentLengthLong();
            final long totalBytes;
            if (code == 206 && len > 0) totalBytes = offset + len;
            else if (len > 0) totalBytes = len;
            else totalBytes = -1;
            synchronized (this) {
                fileName = name;
                downloaded = offset;
                total = totalBytes;
                if (totalBytes > 0 && dir.getUsableSpace() < totalBytes - offset + 10 * 1024 * 1024) {
                    fail("no_space", "Ruang TV tidak cukup buat video ini (butuh "
                            + formatMb(totalBytes) + ").");
                    return;
                }
                persistState();
            }
            emit();
            InputStream in = conn.getInputStream();
            FileOutputStream out = new FileOutputStream(tmp, offset > 0);
            byte[] buf = new byte[65536];
            int n;
            long doneBytes = offset;
            long lastEmitAt = 0;
            try {
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                    doneBytes += n;
                    long now = System.currentTimeMillis();
                    if (now - lastEmitAt >= 300) {
                        lastEmitAt = now;
                        synchronized (this) {
                            downloaded = doneBytes;
                            persistThrottled();
                        }
                        emit();
                    }
                }
                out.flush();
            } finally {
                try { out.close(); } catch (Exception ignored) {}
                try { in.close(); } catch (Exception ignored) {}
            }
            synchronized (this) {
                downloaded = doneBytes;
                persistState();
            }
            if (!tmp.renameTo(fin)) {
                if (!fin.exists()) {
                    fail("no_space", "File selesai diunduh tapi tidak bisa disimpan. Cek ruang TV.");
                    return;
                }
            }
            synchronized (this) {
                videoBytes = doneBytes;
                audioBytes = 0;
            }
            done(name, "Video " + formatMb(doneBytes) + ".");
        } catch (java.io.IOException e) {
            // File .part SENGAJA tidak dihapus: unduhan bisa
            // dilanjutkan nanti dari byte yang sudah ada.
            if (dir.getUsableSpace() < 10 * 1024 * 1024) {
                fail("no_space", "Ruang TV habis saat mengunduh.");
            } else {
                fail("network", "Unduhan putus di tengah jalan: " + shortMsg(e)
                        + " Ketuk unduh lagi buat melanjutkan.");
            }
        } catch (Exception e) {
            fail("network", "Unduhan gagal: " + shortMsg(e));
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** Lemparan khusus: ruang disk tidak cukup (bukan jaringan putus). */
    private static class NoSpaceException extends java.io.IOException {
        NoSpaceException(String m) { super(m); }
    }

    /** Unduh DASH: video MP4 + audio MP4 terpisah, lalu gabung jadi
        satu MP4 pakai MediaMuxer. Progres = gabungan kedua potongan.
        File .part TIDAK PERNAH dihapus saat gagal, supaya bisa
        dilanjutkan nanti. */
    private void downloadDashPair(String vid, String ttl, StreamSet set, String nameHint) {
        File dir = downloadDir(ctx);
        if (dir == null || !dir.isDirectory()) {
            fail("no_space", "Folder unduhan tidak bisa dibuat di TV ini.");
            return;
        }
        String name = nameHint != null ? nameHint : uniqueFileName(dir, sanitizeFileName(ttl));
        File fin = new File(dir, name);
        if (fin.exists()) {
            done(name);
            return;
        }
        File vPart = new File(dir, "." + name + ".video.part");
        File aPart = new File(dir, "." + name + ".audio.part");
        try {
            synchronized (this) { fileName = name; }
            long vOff = vPart.isFile() ? vPart.length() : 0;
            long vBytes = downloadPart(set.videoUrl, vPart, vOff, dir, 0, -1);
            long videoTotal;
            synchronized (this) { videoTotal = total; }
            long aOff = aPart.isFile() ? aPart.length() : 0;
            long aBytes = downloadPart(set.audioUrl, aPart, aOff, dir, vBytes, videoTotal);
            synchronized (this) {
                videoBytes = vPart.length();
                audioBytes = aPart.length();
                persistState();
            }
            Log.i(TAG, "DASH selesai: video " + videoBytes + " byte, audio " + audioBytes + " byte");
            if (!muxParts(vPart, aPart, fin)) {
                fail("mux", "Video dan suara sudah terunduh (Video " + formatMb(videoBytes)
                        + " + Audio " + formatMb(audioBytes)
                        + ") tapi gagal digabung jadi satu file. Ketuk unduh lagi buat mencoba lagi.");
                return;
            }
            try { vPart.delete(); } catch (Exception ignored) {}
            try { aPart.delete(); } catch (Exception ignored) {}
            done(name, "Video " + formatMb(videoBytes) + " + Audio " + formatMb(audioBytes)
                    + ", gambar + suara lengkap.");
        } catch (NoSpaceException e) {
            fail("no_space", "Ruang TV tidak cukup buat video ini.");
        } catch (java.io.IOException e) {
            if (dir.getUsableSpace() < 10 * 1024 * 1024) {
                fail("no_space", "Ruang TV habis saat mengunduh.");
            } else {
                fail("network", "Unduhan putus di tengah jalan: " + shortMsg(e)
                        + " Ketuk unduh lagi buat melanjutkan.");
            }
        } catch (Exception e) {
            fail("network", "Unduhan gagal: " + shortMsg(e));
        }
    }

    /** Unduh satu URL ke satu file .part, dengan HTTP Range kalau
        melanjutkan. baseDownloaded/baseTotal = byte/total potongan
        sebelumnya (buat progres gabungan jalur DASH; 0/-1 kalau
        cuma satu potongan). Mengembalikan jumlah byte akhir file.
        Lempar IOException kalau gagal. */
    private long downloadPart(String urlStr, File tmp, long startOffset,
                              File dir, long baseDownloaded, long baseTotal) throws java.io.IOException {
        long offset = startOffset;
        if (offset > 0 && !tmp.isFile()) offset = 0;
        if (offset > 0 && tmp.length() < offset) offset = tmp.length();
        HttpURLConnection conn = null;
        try {
            conn = openDownloadConnection(urlStr, offset);
            int code = conn.getResponseCode();
            if (code == 416) {
                // Range tidak masuk akal (file .part lebih besar dari
                // video): mulai dari nol.
                try { tmp.delete(); } catch (Exception ignored) {}
                offset = 0;
                conn.disconnect();
                conn = openDownloadConnection(urlStr, 0);
                code = conn.getResponseCode();
            } else if (code == 200 && offset > 0) {
                // Server mengabaikan Range: mulai dari nol.
                try { tmp.delete(); } catch (Exception ignored) {}
                offset = 0;
            }
            if (code != 200 && code != 206) {
                throw new java.io.IOException("HTTP " + code);
            }
            long len = conn.getContentLengthLong();
            final long partTotal;
            if (code == 206 && len > 0) partTotal = offset + len;
            else if (len > 0) partTotal = len;
            else partTotal = -1;
            synchronized (this) {
                downloaded = baseDownloaded + offset;
                total = (baseTotal > 0 && partTotal > 0) ? baseTotal + partTotal
                        : (partTotal > 0 ? partTotal : baseTotal);
                if (partTotal > 0 && dir.getUsableSpace() < partTotal - offset + 10 * 1024 * 1024) {
                    throw new NoSpaceException("ruang tidak cukup");
                }
                persistState();
            }
            emit();
            InputStream in = conn.getInputStream();
            FileOutputStream out = new FileOutputStream(tmp, offset > 0);
            byte[] buf = new byte[65536];
            int n;
            long doneBytes = offset;
            long lastEmitAt = 0;
            try {
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                    doneBytes += n;
                    long now = System.currentTimeMillis();
                    if (now - lastEmitAt >= 300) {
                        lastEmitAt = now;
                        synchronized (this) {
                            downloaded = baseDownloaded + doneBytes;
                            persistThrottled();
                        }
                        emit();
                    }
                }
                out.flush();
            } finally {
                try { out.close(); } catch (Exception ignored) {}
                try { in.close(); } catch (Exception ignored) {}
            }
            synchronized (this) {
                downloaded = baseDownloaded + doneBytes;
                persistState();
            }
            return doneBytes;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** Koneksi GET buat unduh file (Range opsional). */
    private static HttpURLConnection openDownloadConnection(String urlStr, long offset) throws java.io.IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(30000);
        conn.setRequestMethod("GET");
        conn.setRequestProperty("User-Agent", "KaraokeRemote/1.0");
        conn.setInstanceFollowRedirects(true);
        if (offset > 0) conn.setRequestProperty("Range", "bytes=" + offset + "-");
        return conn;
    }

    /** Gabung potongan video + audio jadi satu MP4 pakai MediaMuxer
        bawaan Android. Mengembalikan true kalau file hasil ada, tidak
        kosong, dan TERBUKTI berisi satu trek video + satu trek audio
        (dicek ulang dengan MediaExtractor). Kalau trek video tidak
        ada / tidak berisi sampel, hasil dianggap GAGAL. Jangan
        pernah laporkan "selesai" untuk file yang cuma suara. File
        hasil yang gagal dihapus; potongan .part tetap tersimpan buat
        dicoba lagi. */
    private static boolean muxParts(File video, File audio, File out) {
        MediaMuxer muxer = null;
        MediaExtractor vEx = null;
        MediaExtractor aEx = null;
        try {
            muxer = new MediaMuxer(out.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            vEx = new MediaExtractor();
            vEx.setDataSource(video.getAbsolutePath());
            aEx = new MediaExtractor();
            aEx.setDataSource(audio.getAbsolutePath());
            int vTrack = selectTrack(vEx, "video/");
            int aTrack = selectTrack(aEx, "audio/");
            if (vTrack < 0 || aTrack < 0) {
                Log.w(TAG, "mux: trek tidak ketemu (video=" + vTrack + " audio=" + aTrack + ")");
                return false;
            }
            int vOut = muxer.addTrack(vEx.getTrackFormat(vTrack));
            int aOut = muxer.addTrack(aEx.getTrackFormat(aTrack));
            muxer.start();
            int[] counts = new int[2]; // [sampel video, sampel audio]
            copyInterleaved(vEx, vTrack, vOut, aEx, aTrack, aOut, muxer, counts);
            muxer.stop();
            Log.i(TAG, "mux: sampel video=" + counts[0] + " audio=" + counts[1]);
            if (counts[0] <= 0 || counts[1] <= 0) {
                Log.w(TAG, "mux: salah satu trek kosong");
                try { out.delete(); } catch (Exception ignored) {}
                return false;
            }
            if (!out.isFile() || out.length() <= 0) return false;
            return verifyTracks(out);
        } catch (Exception e) {
            Log.w(TAG, "mux gagal: " + e);
            try { out.delete(); } catch (Exception ignored) {}
            return false;
        } finally {
            try { if (muxer != null) muxer.release(); } catch (Exception ignored) {}
            try { if (vEx != null) vEx.release(); } catch (Exception ignored) {}
            try { if (aEx != null) aEx.release(); } catch (Exception ignored) {}
        }
    }

    /** Buka lagi file hasil gabungan, pastikan ada satu trek video
        dan satu trek audio. true kalau keduanya ada. */
    private static boolean verifyTracks(File out) {
        MediaExtractor ex = null;
        try {
            ex = new MediaExtractor();
            ex.setDataSource(out.getAbsolutePath());
            int v = selectTrack(ex, "video/");
            int a = selectTrack(ex, "audio/");
            Log.i(TAG, "verifikasi hasil: video=" + v + " audio=" + a);
            return v >= 0 && a >= 0;
        } catch (Exception e) {
            Log.w(TAG, "verifikasi hasil gagal: " + e);
            return false;
        } finally {
            try { if (ex != null) ex.release(); } catch (Exception ignored) {}
        }
    }

    /** Indeks track pertama yang MIME-nya diawali prefix, atau -1. */
    private static int selectTrack(MediaExtractor ex, String prefix) {
        for (int i = 0; i < ex.getTrackCount(); i++) {
            MediaFormat f = ex.getTrackFormat(i);
            String mime = f.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith(prefix)) return i;
        }
        return -1;
    }

    /** Salin semua sampel dari dua extractor ke muxer, diurutkan
        menurut waktu tayang supaya suara dan gambar tetap sinkron.
        counts[0] = jumlah sampel video yang ditulis, counts[1] =
        jumlah sampel audio yang ditulis. */
    private static void copyInterleaved(MediaExtractor vEx, int vTrack, int vOut,
                                        MediaExtractor aEx, int aTrack, int aOut,
                                        MediaMuxer muxer, int[] counts) {
        vEx.selectTrack(vTrack);
        aEx.selectTrack(aTrack);
        ByteBuffer vBuf = ByteBuffer.allocate(bufferSizeFor(vEx, vTrack));
        ByteBuffer aBuf = ByteBuffer.allocate(bufferSizeFor(aEx, aTrack));
        MediaCodec.BufferInfo vInfo = new MediaCodec.BufferInfo();
        MediaCodec.BufferInfo aInfo = new MediaCodec.BufferInfo();
        boolean vHas = fillSample(vEx, vBuf, vInfo);
        boolean aHas = fillSample(aEx, aBuf, aInfo);
        while (vHas || aHas) {
            if (aHas && (!vHas || aInfo.presentationTimeUs < vInfo.presentationTimeUs)) {
                muxer.writeSampleData(aOut, aBuf, aInfo);
                counts[1]++;
                aHas = fillSample(aEx, aBuf, aInfo);
            } else if (vHas) {
                muxer.writeSampleData(vOut, vBuf, vInfo);
                counts[0]++;
                vHas = fillSample(vEx, vBuf, vInfo);
            } else {
                break;
            }
        }
    }

    /** Ukuran buffer buat satu track: sampel terbesar menurut format,
        minimal 4 MB (keyframe 720p bisa besar). */
    private static int bufferSizeFor(MediaExtractor ex, int track) {
        try {
            int max = ex.getTrackFormat(track).getInteger(MediaFormat.KEY_MAX_INPUT_SIZE);
            return Math.max(4 * 1024 * 1024, max);
        } catch (Exception e) {
            return 4 * 1024 * 1024;
        }
    }

    /** Baca satu sampel ke buf/info. true = ada sampel, false = habis. */
    private static boolean fillSample(MediaExtractor ex, ByteBuffer buf, MediaCodec.BufferInfo info) {
        buf.clear();
        int size = ex.readSampleData(buf, 0);
        if (size < 0) return false;
        info.set(0, size, ex.getSampleTime(), ex.getSampleFlags());
        ex.advance();
        return true;
    }

    private static String formatMb(long bytes) {
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        return String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0);
    }

    /** Judul -> nama file .mp4 yang aman (tanpa karakter ilegal). */
    static String sanitizeFileName(String title) {
        if (title == null || title.trim().length() == 0) title = "karaoke";
        String base = title.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "-").trim();
        base = base.replaceAll("\\s+", " ").trim();
        if (base.length() == 0) base = "karaoke";
        if (base.length() > 80) base = base.substring(0, 80).trim();
        if (base.endsWith(".")) base = base.substring(0, base.length() - 1);
        if (base.length() == 0) base = "karaoke";
        return base + ".mp4";
    }

    /** Kalau nama sudah ada, tambah " (2)", " (3)", dst. File yang
        sudah selesai TIDAK PERNAH ditimpa. */
    private static String uniqueFileName(File dir, String name) {
        if (!new File(dir, name).exists()) return name;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 2; i < 1000; i++) {
            String cand = base + " (" + i + ")" + ext;
            if (!new File(dir, cand).exists()) return cand;
        }
        return base + " (" + System.currentTimeMillis() + ")" + ext;
    }
}
